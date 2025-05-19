package org.example.core // Или ваш пакет для клиента

import org.example.IO.IOManager
import java.io.IOException
import java.net.ConnectException // Для отлова конкретной ошибки подключения
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.*
import java.util.ArrayDeque // Используем ArrayDeque вместо LinkedList
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
        private const val READ_BUFFER_CAPACITY_CLIENT = 4096
        private const val RECONNECT_DELAY_MS = 5000L // 5 секунд задержка перед повторной попыткой
        private const val INITIAL_CONNECT_TIMEOUT_MS =
            5000L // Таймаут для первой попытки подключения в sendRequestAndWaitForResponse
        private const val DEFAULT_REQUEST_TIMEOUT_MS = 10000L // Таймаут для обычного запроса
    }

    private var connected = AtomicBoolean(false)
    private var connectionPending = AtomicBoolean(false)
    internal var running = AtomicBoolean(true)

    // Используем ArrayDeque для лучшей производительности
    private val pendingRequests = ArrayDeque<ByteBuffer>()

    private var currentResponseState: SerializationUtils.ObjectReaderState? = null
    private var lastReceivedResponse: Response? = null
    private val responseLock = Object()
    private var responseAvailable = AtomicBoolean(false)

    private val nioThread: Thread
    private val logger = Logger.getLogger(ApiClient::class.java.name)

    // Добавляем callback для обновления команд в CommandProcessor
    var onCommandsUpdated: ((List<String>) -> Unit)? = null
    private var initialConnectionAttemptMade = AtomicBoolean(false)


    init {
        logger.level = Level.WARNING
        try {
            // Селектор создается один раз здесь
            selector = Selector.open()
            nioThread = Thread { clientSelectorLoop() }.apply {
                name = "ClientNIOThread"
                isDaemon = true
                start()
            }
            // Первая попытка подключения будет инициирована из clientSelectorLoop, если не подключен
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "ApiClient: Critical error during initialization", e)
            throw e
        }
    }

    private fun initiateConnection() {
        if (!running.get() || connected.get() || connectionPending.get()) {
            if (connected.get()) logger.log(Level.INFO, "ApiClient: Already connected.")
            if (connectionPending.get()) logger.log(Level.INFO, "ApiClient: Connection attempt already in progress.")
            return
        }

        try {
            // Канал создается/пересоздается здесь, если нужно
            if (channel == null || channel?.isOpen == false) {
                channel?.close()
                channel = SocketChannel.open()
                channel!!.configureBlocking(false)
                logger.log(Level.INFO, "ApiClient: New SocketChannel created.")
            }

            // Селектор должен быть уже открыт из init. Если он закрылся, это ошибка.
            val currentSelector = selector
            if (currentSelector == null || !currentSelector.isOpen) {
                logger.log(
                    Level.SEVERE,
                    "ApiClient: Selector is closed or null during initiateConnection. This should not happen."
                )
                // Попытка пересоздать селектор, хотя это указывает на более глубокую проблему
                selector?.close()
                selector = Selector.open()
                // Потребуется перезапустить nioThread или перерегистрировать все каналы, что сложно.
                // Проще остановить клиент, если селектор умер.
                // Для простоты сейчас просто пересоздадим.
                logger.log(Level.WARNING, "ApiClient: Re-opened selector. Consider application stability.")
            }


            connectionPending.set(true)
            ioManager.outputLine("Attempting to connect to server $serverHost:$serverPort...")
            logger.log(Level.INFO, "ApiClient: Initiating connection to $serverHost:$serverPort.")

            val connectedImmediately = channel!!.connect(InetSocketAddress(serverHost, serverPort))

            if (connectedImmediately) {
                logger.log(Level.INFO, "ApiClient: Connected immediately.")
                completeConnectionSetup(channel!!.keyFor(selector)) // Передаем null, т.к. ключ еще не создан для selector
            } else {
                logger.log(Level.INFO, "ApiClient: Connection pending.")
                // Регистрируем на OP_CONNECT. Если канал уже был зарегистрирован, это обновит interestOps.
                channel!!.register(selector, SelectionKey.OP_CONNECT)
            }
            selector?.wakeup()
        } catch (e: ConnectException) {
            connectionPending.set(false)
            // Не устанавливаем connected в false, т.к. это состояние "не подключен"
            logger.log(
                Level.WARNING,
                "ApiClient: Connection refused to $serverHost:$serverPort. Will retry. Error: ${e.message}"
            )
            // Не выводим ошибку в ioManager здесь, чтобы не спамить при автоматических ретраях
            // Следующая попытка будет через RECONNECT_DELAY_MS в clientSelectorLoop
        } catch (e: Exception) {
            connectionPending.set(false)
            logger.log(Level.WARNING, "ApiClient: Error initiating connection: ${e.message}", e)
            // Следующая попытка будет через RECONNECT_DELAY_MS в clientSelectorLoop
        }
    }

    // Вынесенная логика установки после успешного соединения
    private fun completeConnectionSetup(key: SelectionKey?) {
        connectionPending.set(false)
        connected.set(true)
        currentResponseState = SerializationUtils.ObjectReaderState()
        ioManager.outputLine("Successfully connected to server.")
        logger.log(Level.INFO, "ApiClient: Connection setup complete.")

        // Запрашиваем список команд
        requestCommandListUpdate()

        var newOps = SelectionKey.OP_READ
        synchronized(pendingRequests) {
            if (pendingRequests.isNotEmpty()) {
                newOps = newOps or SelectionKey.OP_WRITE
            }
        }
        // Если key null (было мгновенное подключение до регистрации), регистрируем новый.
        // Иначе, обновляем существующий ключ.
        if (key == null || !key.isValid) {
            channel?.register(selector, newOps)
        } else {
            key.interestOps(newOps)
        }
        selector?.wakeup() // На случай если селектор спит
    }

    private fun requestCommandListUpdate() {
        if (!connected.get()) return

        // Создаем специальный запрос для получения списка команд (например, "help")
        // Предполагается, что сервер на "help" или аналогичный запрос всегда возвращает актуальный список команд
        val helpRequest = Request(body = listOf("help"), currentCommandsList = null, vehicle = null)
        val requestBuffer = SerializationUtils.objectToByteBuffer(helpRequest)

        logger.log(Level.INFO, "ApiClient: Queueing 'help' request to update command list.")
        // Добавляем этот запрос в начало очереди, чтобы он обработался первым
        synchronized(pendingRequests) {
            pendingRequests.addFirst(requestBuffer) // addFirst, чтобы он был первым
        }

        channel?.keyFor(selector)?.let { key ->
            if (key.isValid && (key.interestOps() and SelectionKey.OP_WRITE) == 0) {
                key.interestOps(key.interestOps() or SelectionKey.OP_WRITE)
            }
        }
        selector?.wakeup()
        // Ответ на этот запрос будет обработан в handleRead, и там же вызовется onCommandsUpdated
    }


    private fun clientSelectorLoop() {
        logger.log(Level.INFO, "ApiClient: NIOEventLoop started.")
        var lastReconnectAttemptTime = 0L

        while (running.get()) {
            try {
                val currentSelector = selector
                if (currentSelector == null || !currentSelector.isOpen) {
                    logger.log(
                        Level.WARNING,
                        "ApiClient: Selector is null or closed, attempting to re-initialize or exit."
                    )
                    if (running.get()) { // Если мы все еще должны работать
                        try {
                            selector?.close()
                            selector = Selector.open()
                            logger.log(
                                Level.INFO,
                                "ApiClient: Selector re-opened. Any existing channel registrations are lost."
                            )
                            // После пересоздания селектора, если был активный канал, его нужно перерегистрировать.
                            // Но при разрыве связи канал и так закрывается.
                            // Поэтому просто инициируем новое подключение.
                            connected.set(false) // Сбросить состояние подключения
                            connectionPending.set(false)
                            // Немедленно пытаемся подключиться, если селектор был пересоздан
                            if (channel != null && channel!!.isOpen) { // Если канал еще как-то жив
                                try {
                                    channel!!.register(currentSelector, SelectionKey.OP_CONNECT)
                                } catch (e: ClosedChannelException) {
                                    channel = null // Канал уже был закрыт
                                    initiateConnection()
                                }
                            } else {
                                initiateConnection()
                            }
                        } catch (e: IOException) {
                            logger.log(Level.SEVERE, "ApiClient: Failed to re-open selector. Exiting NIO loop.", e)
                            running.set(false)
                        }
                    } else {
                        break // Если не должны работать, выходим
                    }
                    if (!running.get()) break
                }


                // Логика попытки переподключения, если не подключены и не в процессе
                if (!connected.get() && !connectionPending.get() && running.get()) {
                    if (System.currentTimeMillis() - lastReconnectAttemptTime > RECONNECT_DELAY_MS) {
                        logger.log(Level.INFO, "ApiClient: Attempting to reconnect...")
                        initiateConnection()
                        lastReconnectAttemptTime = System.currentTimeMillis()
                        initialConnectionAttemptMade.set(true)
                    }
                }

                val readyCount = currentSelector!!.select(1000) // Таймаут для проверки флагов

                if (!running.get()) break
                if (readyCount == 0) continue

                val selectedKeys = currentSelector.selectedKeys().iterator()
                while (selectedKeys.hasNext()) {
                    val key = selectedKeys.next()
                    selectedKeys.remove()

                    if (!key.isValid) continue

                    try {
                        when {
                            key.isConnectable -> handleConnect(key)
                            key.isReadable -> handleRead(key)
                            key.isWritable -> handleWrite(key)
                        }
                    } catch (e: CancelledKeyException) {
                        logger.log(Level.FINER, "ApiClient: Key cancelled for ${key.channel()}", e)
                        // Обычно означает, что канал был закрыт, handleDisconnect позаботится
                    } catch (e: IOException) {
                        logger.log(Level.WARNING, "ApiClient: IOException on channel ${key.channel()}: ${e.message}.")
                        handleDisconnect(key) // Попытка переподключения будет здесь
                    } catch (e: Exception) {
                        logger.log(
                            Level.SEVERE,
                            "ApiClient: Error processing key for channel ${key.channel()}: ${e.message}",
                            e
                        )
                        handleDisconnect(key) // Попытка переподключения
                    }
                }
            } catch (e: ClosedSelectorException) {
                logger.log(
                    Level.INFO,
                    "ApiClient: Selector closed during select. NIO loop may exit or attempt recovery."
                )
                if (!running.get()) break // Если мы завершаемся, то выходим
                // Попытка восстановить селектор, если это не было сделано выше
            } catch (e: IOException) { // Ошибка в самом select()
                logger.log(Level.WARNING, "ApiClient: IOException in selector loop: ${e.message}", e)
                if (selector?.isOpen == false) {
                    logger.log(
                        Level.INFO,
                        "ApiClient: Selector found closed after IOException. NIO loop may exit or attempt recovery."
                    )
                    if (!running.get()) break
                } else if (running.get()) {
                    // Если селектор жив, но произошла ошибка, возможно, проблема с сетью.
                    // handleDisconnect(null) может быть слишком агрессивно, если нет конкретного ключа.
                    // Попробуем просто продолжить, логика переподключения выше должна сработать.
                    logger.log(Level.INFO, "ApiClient: Continuing selector loop after IOException.")
                }
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "ApiClient: Unexpected error in selector loop: ${e.message}", e)
                running.set(false) // Критическая ошибка
            }
        }
        logger.log(Level.INFO, "ApiClient: NIOEventLoop finished.")
        cleanupResources()
    }

    private fun handleConnect(key: SelectionKey) {
        val socketChannel = key.channel() as SocketChannel
        try {
            if (socketChannel.finishConnect()) {
                logger.log(Level.INFO, "ApiClient: Successfully connected via finishConnect().")
                completeConnectionSetup(key) // Используем новый метод
            }
            // Если finishConnect() вернул false, ключ остается на OP_CONNECT, ждем следующего события
        } catch (e: ConnectException) {
            logger.log(Level.WARNING, "ApiClient: Connection failed in finishConnect(): ${e.message}")
            // Не вызываем handleDisconnect, чтобы не было рекурсии,
            // цикл в clientSelectorLoop сам инициирует новую попытку через RECONNECT_DELAY_MS
            connectionPending.set(false) // Сбрасываем флаг, чтобы initiateConnection мог сработать
            key.cancel() // Отменяем ключ, т.к. это соединение не удалось
            try {
                socketChannel.close()
            } catch (ioe: IOException) { /* ignore */
            }
            channel = null // Сбрасываем канал, чтобы он был пересоздан
        } catch (e: IOException) {
            logger.log(Level.WARNING, "ApiClient: IOException during finishConnect(): ${e.message}")
            handleDisconnect(key) // Для других IOException закрываем и пытаемся переподключиться
        }
    }

    private fun handleRead(key: SelectionKey) {
        val socketChannel = key.channel() as SocketChannel
        val tempBuffer = ByteBuffer.allocate(READ_BUFFER_CAPACITY_CLIENT)
        val state = currentResponseState ?: run {
            logger.log(Level.SEVERE, "ApiClient: CRITICAL - No response state for read. Recreating.")
            currentResponseState = SerializationUtils.ObjectReaderState()
            return
        }

        val numRead: Int
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
                    } else break
                }

                if (state.isLengthRead) {
                    if (state.readObjectBytesFromBuffer(tempBuffer)) {
                        logger.log(Level.FINER, "ApiClient: Response object bytes received.")
                        val response = state.deserializeObject<Response>()
                        if (response != null) {
                            // Проверяем, был ли это ответ на запрос списка команд
                            // (можно сделать более надежно, добавив поле в Request/Response)
                            // Пока что ориентируемся на то, что help-запрос был первым в очереди.
                            // И на то, что newCommandsList не пустой.
                            if (response.newCommandsList.isNotEmpty()) {
                                onCommandsUpdated?.invoke(response.newCommandsList)
                                logger.log(
                                    Level.INFO,
                                    "ApiClient: Command list updated from server. Count: ${response.newCommandsList.size}"
                                )
                            }

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
                            synchronized(responseLock) {
                                lastReceivedResponse = null
                                responseAvailable.set(true)
                                responseLock.notifyAll()
                            }
                        }
                        state.reset()
                    } else break
                }
            }
        }
    }

    private fun handleWrite(key: SelectionKey) {
        val socketChannel = key.channel() as SocketChannel
        synchronized(pendingRequests) {
            val bufferToSend = pendingRequests.peekFirst() // Используем peekFirst для ArrayDeque
            if (bufferToSend == null) {
                if (key.isValid) key.interestOps(SelectionKey.OP_READ)
                return
            }

            try {
                val bytesWritten = socketChannel.write(bufferToSend)
                logger.log(Level.FINER, "ApiClient: Wrote $bytesWritten bytes. Remaining: ${bufferToSend.remaining()}")
            } catch (e: IOException) {
                logger.log(Level.WARNING, "ApiClient: Error writing request: ${e.message}")
                handleDisconnect(key)
                return
            }

            if (!bufferToSend.hasRemaining()) {
                pendingRequests.pollFirst() // Используем pollFirst для ArrayDeque
                logger.log(Level.INFO, "ApiClient: Request sent completely.")
            }

            if (pendingRequests.isEmpty()) {
                if (key.isValid) key.interestOps(SelectionKey.OP_READ)
            }
        }
    }

    private fun handleDisconnect(key: SelectionKey?) {
        logger.log(Level.INFO, "ApiClient: Handling disconnect.")
        key?.cancel()
        channel?.let {
            try {
                it.close()
            } catch (e: IOException) { /* Игнор */
            }
        }
        channel = null // Важно сбросить канал, чтобы он был пересоздан в initiateConnection
        connected.set(false)
        connectionPending.set(false) // Сбрасываем, чтобы initiateConnection мог запуститься
        currentResponseState?.reset()

        synchronized(responseLock) {
            lastReceivedResponse = null
            responseAvailable.set(true)
            responseLock.notifyAll()
        }

        synchronized(pendingRequests) {
            // Решение: не очищать очередь запросов при разрыве,
            // чтобы они отправились после переподключения.
            // Если какие-то запросы специфичны для сессии, их нужно будет обрабатывать иначе.
            // pendingRequests.clear() // ЗАКОММЕНТИРОВАНО
            logger.log(Level.WARNING, "ApiClient: Disconnected. ${pendingRequests.size} requests remain in queue.")
        }

        // Попытка переподключения будет инициирована циклом в clientSelectorLoop
        // через RECONNECT_DELAY_MS, если мы все еще running.
        // Не вызываем initiateConnection() здесь напрямую, чтобы избежать слишком частых попыток
        // и потенциальной рекурсии, если initiateConnection сразу же падает.
        logger.log(Level.INFO, "ApiClient: Disconnect processed. Reconnect will be attempted by selector loop.")
        selector?.wakeup() // Разбудить селектор, чтобы он быстрее проверил состояние и начал реконнект
    }


    fun sendRequestAndWaitForResponse(request: Request, timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS): Response? {
        if (!running.get()) {
            ioManager.error("ApiClient is not running. Cannot send request.")
            return null
        }

        // Если не подключены, ждем автоматического переподключения или первой попытки
        if (!connected.get()) {
            ioManager.outputLine("ApiClient: Not connected. Waiting for connection...")
            val connectWaitStartTime = System.currentTimeMillis()
            // Даем время на автоматическое подключение в фоновом потоке NIO
            // или на первую попытку, если клиент только что запустился.
            val effectiveTimeout =
                if (!initialConnectionAttemptMade.get()) INITIAL_CONNECT_TIMEOUT_MS * 2 else INITIAL_CONNECT_TIMEOUT_MS

            while (!connected.get() && running.get() && (System.currentTimeMillis() - connectWaitStartTime < effectiveTimeout)) {
                synchronized(responseLock) { // Используем responseLock для ожидания, но можно и просто Thread.sleep
                    try {
                        responseLock.wait(100) // Ждем немного, позволяя NIO-потоку подключиться
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        ioManager.error("ApiClient: Waiting for connection interrupted.")
                        return null
                    }
                }
                if (connectionPending.get() && System.currentTimeMillis() - connectWaitStartTime > INITIAL_CONNECT_TIMEOUT_MS / 2) {
                    // Если все еще connectionPending после половины таймаута, даем шанс
                    logger.log(Level.INFO, "ApiClient: Still pending connection, continuing to wait in sendRequest.")
                }
            }

            if (!connected.get()) {
                ioManager.error("ApiClient: Failed to connect to server after timeout. Cannot send request.")
                return null
            }
        }

        val requestBuffer = SerializationUtils.objectToByteBuffer(request)

        synchronized(responseLock) {
            lastReceivedResponse = null
            responseAvailable.set(false)
        }

        synchronized(pendingRequests) {
            pendingRequests.addLast(requestBuffer) // Используем addLast для ArrayDeque
        }

        channel?.keyFor(selector)?.let { key ->
            if (key.isValid && (key.interestOps() and SelectionKey.OP_WRITE) == 0) {
                key.interestOps(key.interestOps() or SelectionKey.OP_WRITE)
            }
        }
        selector?.wakeup()

        val overallStartTime = System.currentTimeMillis()
        synchronized(responseLock) {
            while (!responseAvailable.get() && connected.get() && running.get() && (System.currentTimeMillis() - overallStartTime < timeoutMs)) {
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

            if (!responseAvailable.get()) {
                if (!connected.get()) {
                    ioManager.error("ApiClient: Disconnected while waiting for response.")
                } else {
                    ioManager.error("ApiClient: Timeout waiting for server response.")
                }
                return null
            }
            return lastReceivedResponse
        }
    }

    fun close() {
        logger.log(Level.INFO, "ApiClient: Initiating shutdown...")
        running.set(false)
        selector?.wakeup()
        try {
            nioThread.join(2000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.log(Level.WARNING, "ApiClient: Interrupted while waiting for NIO thread to join.")
        }
        // cleanupResources() вызывается в конце nioThread
        logger.log(Level.INFO, "ApiClient: Shutdown sequence complete.")
    }

    private fun cleanupResources() {
        logger.log(Level.INFO, "ApiClient: Cleaning up resources...")
        try {
            selector?.let { sel -> if (sel.isOpen) sel.close() }
        } catch (e: IOException) {
            logger.log(Level.WARNING, "ApiClient: Error closing selector: ${e.message}", e)
        }
        try {
            channel?.let { ch -> if (ch.isOpen) ch.close() }
        } catch (e: IOException) {
            logger.log(Level.WARNING, "ApiClient: Error closing channel: ${e.message}", e)
        }
        channel = null
        selector = null // Селектор теперь тоже null после закрытия
        connected.set(false)
        connectionPending.set(false)
        synchronized(pendingRequests) {
            pendingRequests.clear()
        }
        logger.log(Level.INFO, "ApiClient: Resources cleaned up.")
    }

    // Эти методы остаются без изменений
    fun returnNewCommands(): MutableList<String> {
        synchronized(responseLock) {
            return lastReceivedResponse?.newCommandsList?.toMutableList() ?: mutableListOf()
        }
    }

    fun resetNewCommands() {
        synchronized(responseLock) {
            lastReceivedResponse = null
        }
    }
}