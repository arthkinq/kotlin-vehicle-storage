package org.example.core // Или ваш пакет для клиента

import org.example.IO.IOManager
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.*
import java.util.LinkedList // Для очереди запросов на отправку
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

class ApiClient(
    private val ioManager: IOManager,
    private val serverHost: String = "localhost",
    private val serverPort: Int = 8888
) {
    private var selector: Selector? = null
    private var channel: SocketChannel? = null

    companion object {
        private const val READ_BUFFER_CAPACITY_CLIENT = 4096 // Или другое подходящее значение
    }
    // Состояния клиента
    @Volatile
    private var connected = AtomicBoolean(false)

    @Volatile
    private var connectionPending = AtomicBoolean(false)

    @Volatile
    private var running = AtomicBoolean(true) // Для управления жизненным циклом потока селектора

    // Очередь запросов, ожидающих отправки. ByteBuffer уже содержит кадр (длина + объект)
    private val pendingRequests = LinkedList<ByteBuffer>()

    // Состояние чтения ответа от сервера
    private var currentResponseState: SerializationUtils.ObjectReaderState? = null

    // Для хранения последнего успешно полученного ответа
    private var lastReceivedResponse: Response? = null

    // Объект для синхронизации ожидания ответа основным потоком
    private val responseLock = Object()

    @Volatile
    private var responseAvailable = AtomicBoolean(false)

    private val nioThread: Thread

    private val logger = Logger.getLogger(ApiClient::class.java.name)

    init {
        logger.level = Level.WARNING // Будут выводиться только WARNING и SEVERE
        try {
            selector = Selector.open()
            nioThread = Thread { clientSelectorLoop() }.apply {
                name = "ClientNIOThread"
                isDaemon = true // Завершится, если основной поток завершится
                start()
            }
            // Инициируем первое подключение
            initiateConnection()
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "ApiClient: Critical error during initialization", e)
            ioManager.error("ApiClient: Failed to initialize networking components: ${e.message}")
            throw e // Пробрасываем, чтобы приложение могло среагировать
        }
    }

    private fun initiateConnection() {
        if (connected.get() || connectionPending.get()) {
            logger.log(Level.INFO, "ApiClient: Connection attempt already in progress or established.")
            return
        }
        try {
            // Создаем или пересоздаем канал и селектор, если это необходимо
            if (channel == null || channel?.isOpen == false) {
                channel?.close() // Закрываем старый, если он был
                channel = SocketChannel.open()
                channel!!.configureBlocking(false)
            }
            if (selector == null || selector?.isOpen == false) {
                selector?.close()
                selector = Selector.open()
            }


            connectionPending.set(true)
            val connectedImmediately = channel!!.connect(InetSocketAddress(serverHost, serverPort))

            if (connectedImmediately) {
                logger.log(Level.INFO, "ApiClient: Connected immediately to $serverHost:$serverPort.")
                connectionPending.set(false)
                connected.set(true)
                currentResponseState = SerializationUtils.ObjectReaderState()
                var initialOps = SelectionKey.OP_READ
                synchronized(pendingRequests) {
                    if (pendingRequests.isNotEmpty()) {
                        initialOps = initialOps or SelectionKey.OP_WRITE
                    }
                }
                channel!!.register(selector, initialOps) // Регистрируем на нужные операции
                ioManager.outputLine("Connected to server.")
            } else {
                logger.log(Level.INFO, "ApiClient: Connection pending to $serverHost:$serverPort.")
                // Регистрируем на OP_CONNECT, чтобы finishConnect() был вызван в handleConnect
                channel!!.register(selector, SelectionKey.OP_CONNECT)
            }
            selector?.wakeup()
        } catch (e: Exception) {
            connectionPending.set(false)
            connected.set(false) // Если была ошибка, мы не подключены
            logger.log(Level.WARNING, "ApiClient: Error initiating connection: ${e.message}", e)
            ioManager.error("ApiClient: Could not initiate connection to server: ${e.message}")
            // Здесь можно запланировать ретрай или пробросить ошибку
        }
    }

    private fun clientSelectorLoop() {
        logger.log(Level.INFO, "ApiClient: NIOEventLoop started.")
        while (running.get()) {
            try {
                val currentSelector = selector
                if (currentSelector == null || !currentSelector.isOpen) {
                    logger.log(Level.INFO, "ApiClient: Selector is null or closed, exiting NIO loop.")
                    running.set(false) // Устанавливаем флаг для выхода
                    break // Выходим из цикла while
                }

                // Блокируемся здесь, пока не будет событий или таймаута
                val readyCount = currentSelector.select(1000)

                if (!running.get()) { // Проверяем флаг еще раз после select
                    break
                }

                if (readyCount == 0) { // Таймаут, нет событий
                    continue // Переходим к следующей итерации while
                }

                val selectedKeys = currentSelector.selectedKeys().iterator()
                while (selectedKeys.hasNext()) {
                    val key = selectedKeys.next()
                    selectedKeys.remove()

                    if (!key.isValid) {
                        continue // Переходим к следующему ключу
                    }

                    try {
                        when {
                            key.isConnectable -> handleConnect(key)
                            key.isReadable -> handleRead(key)
                            key.isWritable -> handleWrite(key)
                        }
                    } catch (e: CancelledKeyException) {
                        logger.log(Level.FINER, "ApiClient: Key cancelled for ${key.channel()}", e)
                    } catch (e: IOException) {
                        logger.log(
                            Level.WARNING,
                            "ApiClient: IOException on channel ${key.channel()}: ${e.message}. Closing and attempting reconnect."
                        )
                        handleDisconnect(key)
                    } catch (e: Exception) {
                        logger.log(
                            Level.SEVERE,
                            "ApiClient: Error processing key for channel ${key.channel()}: ${e.message}",
                            e
                        )
                        handleDisconnect(key)
                    }
                }
            } catch (e: ClosedSelectorException) {
                logger.log(Level.INFO, "ApiClient: Selector closed during select operation. NIO loop exiting.")
                running.set(false) // Выход из цикла
            } catch (e: IOException) {
                logger.log(Level.WARNING, "ApiClient: IOException in selector loop: ${e.message}", e)
                if (selector?.isOpen == false) {
                    running.set(false) // Если селектор закрыт, выходим
                } else {
                    // Попытка справиться с ошибкой, если селектор еще жив
                    // Например, пересоздать канал, если ошибка связана с ним
                    handleDisconnect(null) // Общий обработчик разрыва
                }
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "ApiClient: Unexpected error in selector loop: ${e.message}", e)
                running.set(false) // Критическая ошибка, останавливаем цикл
            }
        }
        logger.log(Level.INFO, "ApiClient: NIOEventLoop finished.")
        cleanupResources() // Очистка при выходе из цикла
    }

    private fun handleConnect(key: SelectionKey) {
        val socketChannel = key.channel() as SocketChannel
        try {
            if (socketChannel.finishConnect()) {
                connectionPending.set(false)
                connected.set(true)
                logger.log(Level.INFO, "ApiClient: Successfully connected to server.")
                ioManager.outputLine("Connected to server.") // Информируем пользователя

                currentResponseState = SerializationUtils.ObjectReaderState()
                // Теперь интересуемся чтением и, если есть что слать, записью
                var newOps = SelectionKey.OP_READ
                synchronized(pendingRequests) {
                    if (pendingRequests.isNotEmpty()) {
                        newOps = newOps or SelectionKey.OP_WRITE
                    }
                }
                key.interestOps(newOps)
            }
            // Если finishConnect() вернул false, ключ остается на OP_CONNECT
        } catch (e: IOException) {
            logger.log(Level.WARNING, "ApiClient: Connection attempt failed: ${e.message}")
            ioManager.error("ApiClient: Server connection failed: ${e.message}")
            handleDisconnect(key) // Обрабатываем как разрыв соединения
        }
    }

    private fun handleRead(key: SelectionKey) {
        val socketChannel = key.channel() as SocketChannel
        val tempBuffer = ByteBuffer.allocate(READ_BUFFER_CAPACITY_CLIENT) // Теперь константа доступна
        val state = currentResponseState
            ?: run {
                logger.log(
                    Level.SEVERE,
                    "ApiClient: CRITICAL - No response state for read on connected channel. Recreating."
                )
                currentResponseState = SerializationUtils.ObjectReaderState() // Попытка восстановить
                return // или handleDisconnect(key)
            }

        var numRead: Int

        try {
            numRead = socketChannel.read(tempBuffer)
        } catch (e: IOException) {
            logger.log(Level.WARNING, "ApiClient: Error reading from server: ${e.message}")
            handleDisconnect(key)
            return
        }

        if (numRead == -1) {
            logger.log(Level.INFO, "ApiClient: Server closed connection (EOF).")
            handleDisconnect(key)
            return
        }

        if (numRead > 0) {
            tempBuffer.flip()
            logger.log(Level.FINER, "ApiClient: Read $numRead bytes from server.")

            while (tempBuffer.hasRemaining()) {
                if (!state.isLengthRead) {
                    if (state.readLengthFromBuffer(tempBuffer)) {
                        logger.log(Level.FINER, "ApiClient: Response length ${state.expectedLength} received.")
                    } else break // Длина не вся, ждем еще
                }

                if (state.isLengthRead) {
                    if (state.readObjectBytesFromBuffer(tempBuffer)) {
                        logger.log(Level.FINER, "ApiClient: Response object bytes received.")
                        val response = state.deserializeObject<Response>()
                        if (response != null) {
                            synchronized(responseLock) {
                                lastReceivedResponse = response
                                responseAvailable.set(true)
                                responseLock.notifyAll()
                            }
                            logger.log(
                                Level.INFO,
                                "ApiClient: Response processed: ${response.responseText.take(50)}..."
                            )
                        } else {
                            logger.log(Level.WARNING, "ApiClient: Failed to deserialize response object.")
                            // Сигнализируем об ошибке, если основной поток ждет
                            synchronized(responseLock) {
                                lastReceivedResponse = null // Явно указываем на ошибку
                                responseAvailable.set(true) // Чтобы wait не висел вечно
                                responseLock.notifyAll()
                            }
                        }
                        state.reset() // Готовимся к следующему ответу
                    } else break // Объект не весь, ждем еще
                }
            }
        }
    }

    private fun handleWrite(key: SelectionKey) {
        val socketChannel = key.channel() as SocketChannel
        synchronized(pendingRequests) {
            val bufferToSend = pendingRequests.peek() // Смотрим, не удаляя
            if (bufferToSend == null) { // Очередь пуста
                key.interestOps(SelectionKey.OP_READ) // Больше не интересуемся записью
                return
            }

            try {
                val bytesWritten = socketChannel.write(bufferToSend)
                logger.log(
                    Level.FINER,
                    "ApiClient: Wrote $bytesWritten bytes of request. Remaining: ${bufferToSend.remaining()}"
                )
            } catch (e: IOException) {
                logger.log(Level.WARNING, "ApiClient: Error writing request to server: ${e.message}")
                handleDisconnect(key) // Ошибка записи, разрываем соединение
                // Запрос останется в очереди, попробуем отправить при переподключении
                return
            }

            if (!bufferToSend.hasRemaining()) {
                pendingRequests.poll() // Полностью отправлен, удаляем из очереди
                logger.log(Level.INFO, "ApiClient: Request sent completely.")
            }

            // Если очередь пуста после отправки, снимаем интерес к записи
            if (pendingRequests.isEmpty()) {
                key.interestOps(SelectionKey.OP_READ)
            }
            // Если в bufferToSend еще есть данные или в очереди есть другие запросы,
            // OP_WRITE остается, и handleWrite будет вызван снова.
        }
    }

    private fun handleDisconnect(key: SelectionKey?) {
        logger.log(Level.INFO, "ApiClient: Handling disconnect.")
        key?.cancel()
        channel?.let {
            try {
                it.close()
            } catch (e: IOException) { /* Игнорируем */
            }
        }
        channel = null // Важно сбросить канал
        connected.set(false)
        connectionPending.set(false)
        currentResponseState?.reset()

        // Сигнализируем ожидающему потоку, что произошла ошибка
        synchronized(responseLock) {
            lastReceivedResponse = null // Явно указываем на ошибку
            responseAvailable.set(true) // Чтобы wait не висел вечно
            responseLock.notifyAll()
        }

        // Очищаем очередь запросов при разрыве, т.к. их состояние неясно
        // Или можно реализовать логику сохранения и повторной отправки
        synchronized(pendingRequests) {
            // pendingRequests.clear() // Или не очищать, а пытаться отправить при реконнекте
            logger.log(Level.WARNING, "ApiClient: Disconnected. ${pendingRequests.size} requests were in queue.")
        }

        // Пытаемся переподключиться через некоторое время
        // Это упрощенный вариант, в реальности может потребоваться более сложная логика
        if (running.get()) { // Переподключаемся только если клиент еще должен работать
            logger.log(Level.INFO, "ApiClient: Scheduling reconnect attempt...")
            // Можно использовать ScheduledExecutorService для отложенного реконнекта
            // Для простоты, можно просто вызвать initiateConnection() через некоторое время
            // или установить флаг, который проверит основной поток или другой механизм
            Thread.sleep(3000) // Простая пауза
            initiateConnection()
        }
    }


    fun sendRequestAndWaitForResponse(request: Request, timeoutMs: Long = 10000): Response? {
        if (!running.get()) {
            ioManager.error("ApiClient is not running. Cannot send request.")
            return null
        }

        if (!connected.get()) {
            ioManager.outputLine("ApiClient: Not connected. Attempting to connect...")
            initiateConnection() // Запускаем процесс подключения, если еще не запущен

            // Даем время на подключение в фоновом потоке NIO
            val connectStartTime = System.currentTimeMillis()
            while (!connected.get() && connectionPending.get() && (System.currentTimeMillis() - connectStartTime < 5000)) { // Таймаут на попытку подключения
                Thread.sleep(100)
            }
            if (!connected.get()) {
                ioManager.error("ApiClient: Failed to connect to server. Cannot send request.")
                return null
            }
        }

        val requestBuffer = SerializationUtils.objectToByteBuffer(request) // Сериализуем с кадрированием

        // Сброс состояния для ожидания нового ответа
        synchronized(responseLock) {
            lastReceivedResponse = null
            responseAvailable.set(false)
        }

        // Добавляем запрос в очередь и "будим" селектор, чтобы он зарегистрировал на OP_WRITE, если нужно
        synchronized(pendingRequests) {
            pendingRequests.add(requestBuffer)
        }
        channel?.keyFor(selector)?.let { key ->
            if (key.isValid && (key.interestOps() and SelectionKey.OP_WRITE) == 0) {
                key.interestOps(key.interestOps() or SelectionKey.OP_WRITE)
            }
        }
        selector?.wakeup() // Убедимся, что селектор отреагирует на изменение interestOps

        // Ожидаем ответа
        val overallStartTime = System.currentTimeMillis()
        synchronized(responseLock) {
            while (!responseAvailable.get() && (System.currentTimeMillis() - overallStartTime < timeoutMs)) {
                val waitTime = timeoutMs - (System.currentTimeMillis() - overallStartTime)
                if (waitTime <= 0) break
                try {
                    responseLock.wait(waitTime)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    ioManager.error("ApiClient: Waiting for response interrupted.")
                    return null
                }
            }

            if (!responseAvailable.get()) { // Таймаут
                ioManager.error("ApiClient: Timeout waiting for server response.")
                return null
            }
            // responseAvailable is true here
            return lastReceivedResponse // Может быть null, если пришла ошибка
        }
    }

    fun close() {
        logger.log(Level.INFO, "ApiClient: Initiating shutdown...")
        running.set(false) // Сигнал для NIO потока на завершение
        selector?.wakeup() // Разбудить селектор, если он в select()
        try {
            nioThread.join(2000) // Ждем завершения NIO потока
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.log(Level.WARNING, "ApiClient: Interrupted while waiting for NIO thread to join.")
        }
        cleanupResources() // Финальная очистка
        logger.log(Level.INFO, "ApiClient: Shutdown complete.")
    }

    private fun cleanupResources() {
        logger.log(Level.FINER, "ApiClient: Cleaning up resources...")
        try {
            selector?.let { if (it.isOpen) it.close() }
        } catch (e: IOException) {
            logger.log(Level.WARNING, "ApiClient: Error closing selector: ${e.message}", e)
        }
        try {
            channel?.let { if (it.isOpen) it.close() }
        } catch (e: IOException) {
            logger.log(Level.WARNING, "ApiClient: Error closing channel: ${e.message}", e)
        }
        channel = null
        selector = null
        connected.set(false)
        connectionPending.set(false)
        synchronized(pendingRequests) {
            pendingRequests.clear()
        }
    }

    // Методы для получения команд (остаются для совместимости с CommandProcessor)
    fun returnNewCommands(): MutableList<String> {
        synchronized(responseLock) {
            return lastReceivedResponse?.newCommandsList ?: mutableListOf()
        }
    }

    fun resetNewCommands() {
        // Смысл этой операции теперь меньше, т.к. lastReceivedResponse очищается
        // перед каждым новым sendRequestAndWaitForResponse
        synchronized(responseLock) {
            lastReceivedResponse = null
        }
    }
}

// Добавьте в ваш SerializationUtils.kt или в компаньон ApiClient
// object SerializationUtils {
//    const val READ_BUFFER_CAPACITY_CLIENT = 4096 // Для примера
//    // ... остальной код SerializationUtils ...
// }