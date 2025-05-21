package core

import myio.IOManager
import common.Request
import common.SerializationUtils
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.NetworkInterface
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.channels.*
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

class ApiServer(
    private val commandProcessor: CommandProcessor, private val ioManager: IOManager
) {
    private val logger = Logger.getLogger(ApiServer::class.java.name)

    companion object {
        private const val DEFAULT_PORT = 8888
        private const val READ_BUFFER_CAPACITY = 4096
    }

    @Volatile
    private var selector: Selector? = null

    @Volatile
    private var serverSocketChannel: ServerSocketChannel? = null

    // Потокобезопасные коллекции для хранения состояний клиентов
    private val clientReadStates = ConcurrentHashMap<SocketChannel, SerializationUtils.ObjectReaderState>()
    private val clientWriteBuffers =
        ConcurrentHashMap<SocketChannel, ByteBuffer>()

    @Volatile
    private var running = false // Флаг для основного цикла сервера

    fun startServer(port: Int = DEFAULT_PORT) {
        logger.log(Level.INFO, "NIO TCP Server is preparing to start on port $port.")
        running = true

        try {
            selector = Selector.open()
            serverSocketChannel = ServerSocketChannel.open()
            serverSocketChannel!!.configureBlocking(false)
            serverSocketChannel!!.socket().bind(InetSocketAddress(port))
            serverSocketChannel!!.register(selector, SelectionKey.OP_ACCEPT)

            logger.log(Level.INFO, "Server started. Listening on ${serverSocketChannel!!.localAddress}")

            // Админская консоль
            Thread { adminConsoleLoop() }.apply {
                name = "AdminConsoleThread"
                isDaemon = true
                start()
            }
            logger.log(Level.INFO, "Admin console input thread started.")

            // Основной цикл обработки событий селектора
            while (running && serverSocketChannel!!.isOpen && selector!!.isOpen) {
                try {
                    val readyChannels = selector!!.select(1000) // Таймаут, чтобы можно было проверить running

                    if (!running) break

                    if (readyChannels == 0) {
                        continue
                    }

                    val selectedKeys = selector!!.selectedKeys().iterator()
                    while (selectedKeys.hasNext()) {
                        val key = selectedKeys.next()
                        selectedKeys.remove()

                        if (!key.isValid) {
                            continue
                        }

                        try {
                            when {
                                key.isAcceptable -> handleAccept(key)
                                key.isReadable -> handleRead(key)
                                key.isWritable -> handleWrite(key)
                            }
                        } catch (e: CancelledKeyException) {
                            logger.log(Level.FINER, "Key cancelled for ${key.channel()}", e)
                            cleanupClient(key.channel() as? SocketChannel, key)
                        } catch (e: IOException) {
                            logger.log(
                                Level.WARNING, "IOException on channel ${key.channel()}: ${e.message}."
                            )
                            cleanupClient(key.channel() as? SocketChannel, key)
                        } catch (e: Exception) {
                            logger.log(
                                Level.SEVERE, "Error processing key for channel ${key.channel()}: ${e.message}", e
                            )
                            cleanupClient(key.channel() as? SocketChannel, key)
                        }
                    }
                } catch (e: ClosedSelectorException) {
                    logger.log(Level.INFO, "Selector closed, server shutting down.")
                    running = false // Выход из цикла
                } catch (e: IOException) {
                    if (!selector!!.isOpen) {
                        logger.log(Level.INFO, "Selector closed during select (IOException), shutting down.")
                        running = false
                    } else {
                        logger.log(Level.WARNING, "IOException in select loop: ${e.message}", e)
                    }
                } catch (e: Exception) {
                    logger.log(Level.SEVERE, "Unexpected error in server select loop: ${e.message}", e)
                    running = false // Критическая ошибка, останавливаем сервер
                }
            }
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Critical server error during startup: ${e.message}", e)
        } finally {
            shutdownServerInternals()
            logger.log(Level.INFO, "Server stopped.")
        }
    }

    private fun handleAccept(key: SelectionKey) {
        val serverChannel = key.channel() as ServerSocketChannel
        val clientChannel = serverChannel.accept()
        if (clientChannel != null) {
            clientChannel.configureBlocking(false)
            clientChannel.register(selector, SelectionKey.OP_READ)
            clientReadStates[clientChannel] = SerializationUtils.ObjectReaderState()
            logger.log(Level.INFO, "Accepted connection from: ${clientChannel.remoteAddress}")
        }
    }

    private fun handleRead(key: SelectionKey) {
        val clientChannel = key.channel() as SocketChannel
        val readState = clientReadStates[clientChannel] ?: run {
            logger.log(Level.WARNING, "No read state for client ${clientChannel.remoteAddress}. Closing.")
            cleanupClient(clientChannel, key)
            return
        }

        val readBuffer = ByteBuffer.allocate(READ_BUFFER_CAPACITY)
        val numRead: Int

        try {
            numRead = clientChannel.read(readBuffer)
        } catch (e: IOException) {
            logger.log(
                Level.INFO, "Client ${clientChannel.remoteAddress} likely closed connection during read: ${e.message}"
            )
            cleanupClient(clientChannel, key)
            return
        }

        if (numRead == -1) {
            logger.log(Level.INFO, "Client ${clientChannel.remoteAddress} has closed the channel gracefully.")
            cleanupClient(clientChannel, key)
            return
        }

        if (numRead > 0) {
            readBuffer.flip() // Готовим прочитанные данные для ObjectReaderState

            while (readBuffer.hasRemaining()) { // Пока есть данные в нашем временном буфере
                if (!readState.isLengthRead) {
                    if (readState.readLengthFromBuffer(readBuffer)) {
                        logger.log(
                            Level.FINER, "Length read: ${readState.expectedLength} for ${clientChannel.remoteAddress}"
                        )
                    } else {
                        break
                    }
                }

                if (readState.isLengthRead) { // Проверяем еще раз, так как readLengthFromBuffer мог изменить состояние
                    if (readState.readObjectBytesFromBuffer(readBuffer)) {
                        logger.log(Level.FINER, "Object bytes read for ${clientChannel.remoteAddress}")
                        val request: Request? = readState.deserializeObject() // Десериализуем
                        if (request != null) {
                            logger.log(
                                Level.INFO, "Received request from ${clientChannel.remoteAddress}: command='${
                                    request.body.getOrNull(0)
                                }'"
                            )

                            val response = commandProcessor.processCommand(request.body, request.vehicle)

                            response.clearCommandDescriptors()
                            response.addCommandDescriptors(commandProcessor.getCommandDescriptors())
                            val responseBufferToSend = SerializationUtils.objectToByteBuffer(response)

                            try {
                                clientChannel.write(responseBufferToSend)
                                if (responseBufferToSend.hasRemaining()) {
                                    clientWriteBuffers[clientChannel] = responseBufferToSend
                                    key.interestOps(SelectionKey.OP_READ or SelectionKey.OP_WRITE)
                                    logger.log(
                                        Level.INFO,
                                        "Response for ${clientChannel.remoteAddress} partially sent, scheduling for write."
                                    )
                                } else {
                                    logger.log(Level.INFO, "Response sent completely to ${clientChannel.remoteAddress}")
                                }
                            } catch (e: IOException) {
                                logger.log(
                                    Level.WARNING,
                                    "IOException during initial write to ${clientChannel.remoteAddress}: ${e.message}"
                                )
                                cleanupClient(clientChannel, key)
                                return // Выходим из handleRead для этого клиента
                            }
                        } else {
                            logger.log(
                                Level.WARNING,
                                "Failed to deserialize request from ${clientChannel.remoteAddress}. Invalid data format."
                            )
                        }
                        readState.reset()
                    } else {
                        break
                    }
                }
            }
        }
    }

    private fun handleWrite(key: SelectionKey) {
        val clientChannel = key.channel() as SocketChannel
        val buffer = clientWriteBuffers[clientChannel]

        if (buffer == null || !buffer.hasRemaining()) {
            clientWriteBuffers.remove(clientChannel)
            key.interestOps(SelectionKey.OP_READ)
            if (buffer != null && !buffer.hasRemaining()) {
                logger.log(Level.FINER, "Buffer for ${clientChannel.remoteAddress} was already empty on write.")
            }
            return
        }

        try {
            val bytesWritten = clientChannel.write(buffer)
            logger.log(
                Level.FINER,
                "Wrote $bytesWritten bytes of pending data to ${clientChannel.remoteAddress}. Remaining: ${buffer.remaining()}"
            )
        } catch (e: IOException) {
            logger.log(Level.WARNING, "IOException during write to ${clientChannel.remoteAddress}: ${e.message}")
            cleanupClient(clientChannel, key)
            return
        }

        if (!buffer.hasRemaining()) {
            // Все данные отправлены
            clientWriteBuffers.remove(clientChannel)
            key.interestOps(SelectionKey.OP_READ)
            logger.log(Level.INFO, "Finished writing pending response to ${clientChannel.remoteAddress}")
        }
    }

    private fun cleanupClient(clientChannel: SocketChannel?, key: SelectionKey?) {
        if (clientChannel == null) return
        logger.log(
            Level.INFO, "Cleaning up client ${
                try {
                    clientChannel.remoteAddress
                } catch (e: Exception) {
                    "N/A"
                }
            }"
        )
        clientReadStates.remove(clientChannel)
        clientWriteBuffers.remove(clientChannel)
        try {
            clientChannel.close()
        } catch (e: IOException) {
            logger.log(Level.WARNING, "Error closing client channel during cleanup: ${e.message}")
        }
        key?.cancel()
    }

    private fun adminConsoleLoop() {
        logger.log(Level.INFO, "AdminConsoleThread: Started.")
        try {
            while (running) { // Проверяем флаг running
                if (!System.`in`.bufferedReader()
                        .ready() && !running
                ) { // Проверка, если ввод не готов и сервер останавливается
                    break
                }
                val serverAdminCommand = ioManager.readLine() // Может блокироваться
                if (!running && serverAdminCommand.isBlank()) { // Если сервер останавливается во время чтения
                    break
                }
                if (serverAdminCommand.isBlank() && !running) break

                logger.log(Level.FINER, "AdminConsoleThread: Received command: '$serverAdminCommand'")

                when (serverAdminCommand.trim().lowercase()) {
                    "exitadmin" -> {
                        logger.log(Level.INFO, "AdminConsoleThread: Processing 'exitAdmin'.")
                        commandProcessor.processCommand(listOf("save"), null)
                        running = false // Устанавливаем флаг для остановки основного цикла
                        selector?.wakeup()
                        val response = commandProcessor.processCommand(listOf("save"), null)
                        logger.log(Level.INFO, "Save command result: ${response.responseText}")
                        logger.log(Level.INFO, "AdminConsoleThread: Server shutdown initiated.")
                        return
                    }

                    "saveadmin" -> {
                        logger.log(Level.INFO, "AdminConsoleThread: Processing 'saveAdmin'.")
                        val response = commandProcessor.processCommand(listOf("save"), null)
                        logger.log(Level.INFO, "Save command result: ${response.responseText}")
                    }

                    else -> {
                        if (serverAdminCommand.isNotBlank()) {
                            logger.log(Level.INFO, "AdminConsoleThread: Unknown command: '$serverAdminCommand'")
                        }
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.log(Level.INFO, "AdminConsoleThread: Interrupted.")
        } catch (e: IOException) {
            if (e.message?.contains(
                    "Stream closed",
                    ignoreCase = true
                ) == true || e.message?.contains("Bad file descriptor", ignoreCase = true) == true && !running
            ) {
                logger.log(Level.INFO, "AdminConsoleThread: System.in stream closed, thread finishing.")
            } else if (running) { // Логируем ошибку только если сервер еще должен работать
                logger.log(Level.SEVERE, "AdminConsoleThread: IOException: ${e.message}", e)
            }
        } catch (e: Exception) {
            if (running) {
                logger.log(Level.SEVERE, "AdminConsoleThread: Unexpected error: ${e.message}", e)
            }
        } finally {
            logger.log(Level.INFO, "AdminConsoleThread: Finished.")
        }
    }

    private fun shutdownServerInternals() {
        logger.log(Level.INFO, "Shutting down server internals...")
        running = false
        try {
            selector?.let {
                if (it.isOpen) {
                    // Отменить все ключи и закрыть все каналы, зарегистрированные с селектором
                    it.keys().forEach { key ->
                        try {
                            key.channel()?.close()
                        } catch (e: IOException) { /* Игнорируем */
                        }
                        key.cancel()
                    }
                    it.close()
                    logger.log(Level.INFO, "Selector closed.")
                }
            }
            serverSocketChannel?.let {
                if (it.isOpen) {
                    it.close()
                    logger.log(Level.INFO, "ServerSocketChannel closed.")
                }
            }
        } catch (e: IOException) {
            logger.log(Level.WARNING, "Error during shutdown of selector/serverSocketChannel: ${e.message}", e)
        }
        clientReadStates.clear()
        clientWriteBuffers.clear()
    }
}