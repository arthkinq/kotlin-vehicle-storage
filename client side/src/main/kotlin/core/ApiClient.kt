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
    // Selector для управления каналов ввода вывода
    private var selector: Selector? = null

    // SocketChannel: NIO канал для TCP-соединения с сервером
    private var channel: SocketChannel? = null

    companion object {
        // Константа: начальная ёмкость буфера для чтения данных от сервера
        private const val READ_BUFFER_CAPACITY_CLIENT = 4096
    }

    // Состояния клиента
    private var connected = AtomicBoolean(false)

    // Флаг: true, если в данный момент идет попытка подключения к серверу
    private var connectionPending = AtomicBoolean(false)

    // Флаг: true, пока клиент должен продолжать работу
    // Используется для управления жизненным циклом потока selector
    private var running = AtomicBoolean(true)

    // Очередь запросов, ожидающих отправки. ByteBuffer уже содержит кадр (длина + объект)
    private val pendingRequests = LinkedList<ByteBuffer>()

    // Состояние чтения ответа от сервера для чтения сначала длины потом объекта
    private var currentResponseState: SerializationUtils.ObjectReaderState? = null

    // Для хранения последнего успешно полученного ответа
    private var lastReceivedResponse: Response? = null

    // Объект для синхронизации ожидания ответа основным потоком
    private val responseLock = Object()

    // Флаг: true, если новый ответ от сервера получен и доступен
    private var responseAvailable = AtomicBoolean(false)

    // Поток для цикла selector
    private val nioThread: Thread

    // Логгер для записи событий и ошибок класса ApiClient.
    private val logger = Logger.getLogger(ApiClient::class.java.name)

    init {
        logger.level = Level.WARNING // Будут выводиться только WARNING и SEVERE
        try {
            selector = Selector.open() // Создаем новый Selector.
            nioThread = Thread { clientSelectorLoop() }.apply {
                name = "ClientNIOThread"
                isDaemon = true // Завершится, если основной поток завершится
                start() //Запускаем
            }
            // Инициируем первое подключение
            initiateConnection()
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "ApiClient: Critical error during initialization", e)
            throw e // Пробрасываем, чтобы приложение могло среагировать
        }
    }

    private fun initiateConnection() {
        // Если уже подключены или идет процесс подключения ждем
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

            // Пытаемся подключиться к серверу
            // channel!!.connect() в неблокирующем режиме обычно возвращает false, если подключение не произошло мгновенно
            val connectedImmediately = channel!!.connect(InetSocketAddress(serverHost, serverPort))

            if (connectedImmediately) {
                logger.log(Level.INFO, "ApiClient: Connected immediately to $serverHost:$serverPort.")
                connectionPending.set(false)
                connected.set(true)
                currentResponseState = SerializationUtils.ObjectReaderState() // Делаем состояние для чтения ответа
                var initialOps = SelectionKey.OP_READ // Изначально фокусируемся на чтении (?)
                // Синхронизация для безопасного доступа к очереди запросов (?)
                synchronized(pendingRequests) {
                    if (pendingRequests.isNotEmpty()) {
                        initialOps =
                            initialOps or SelectionKey.OP_WRITE // Если есть запросы в очереди на отправку то фокусируемся на записи
                    }
                }
                channel!!.register(selector, initialOps) // Регистрируем на нужные операции, которые мы до этого выбрали
                ioManager.outputLine("Connected to server.")
            } else {
                logger.log(Level.INFO, "ApiClient: Connection pending to $serverHost:$serverPort.")
                // Меняем режим на OP Connect
                channel!!.register(selector, SelectionKey.OP_CONNECT)
            }
            selector?.wakeup() // Будим селектор, если он заблокирован в select чтобы он обработал новую регистрацию
        } catch (e: Exception) {
            connectionPending.set(false)
            connected.set(false) // Если была ошибка, мы не подключены
            logger.log(Level.WARNING, "ApiClient: Error initiating connection: ${e.message}", e)
        }
    }

    // Основной цикл обработки событий NIO
    private fun clientSelectorLoop() {
        logger.log(Level.INFO, "ApiClient: NIOEventLoop started.")
        while (running.get()) { //Пока мы работает или пытаемся
            try {
                val currentSelector = selector
                if (currentSelector == null || !currentSelector.isOpen) {
                    logger.log(Level.INFO, "ApiClient: Selector is null or closed, exiting NIO loop.")
                    running.set(false) // Дальше не идем гиблое дело
                    break // Выходим из цикла while
                }

                // Блокируемся здесь, пока не будет событий или таймаута (?)
                val readyCount = currentSelector.select(1000)

                if (!running.get()) {
                    break
                }

                if (readyCount == 0) { // Таймаут, нет событий
                    continue // Переходим к следующей итерации
                }
                // Получаем ключи каналов на которых что то произошло
                val selectedKeys = currentSelector.selectedKeys().iterator()
                while (selectedKeys.hasNext()) {
                    val key = selectedKeys.next() // Получаем ключ...
                    selectedKeys.remove() //и удаляем его из набора

                    if (!key.isValid) { // Если ключ НЕ все еще действительный те канал ЗАКРЫТ
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
                    handleDisconnect(null)
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
                ioManager.outputLine("Connected to server.")
                //TODO LOAD NEW COMMANDS
                currentResponseState =
                    SerializationUtils.ObjectReaderState() // Инициализируем состояние для чтения ответа
                var newOps = SelectionKey.OP_READ
                synchronized(pendingRequests) { /// Синхронизация для безопасного доступа к очереди (?)
                    if (pendingRequests.isNotEmpty()) {
                        newOps = newOps or SelectionKey.OP_WRITE //Если
                    }
                }
                key.interestOps(newOps) // Меняем фокус
            }
            // Если finishConnect() вернул false, ключ остается на OP_CONNECT
        } catch (e: IOException) {
            logger.log(Level.WARNING, "ApiClient: Connection attempt failed: ${e.message}")
            handleDisconnect(key)
        }
    }

    private fun handleRead(key: SelectionKey) {
        val socketChannel = key.channel() as SocketChannel
        // Выделяем временный буфер для чтения данных
        val tempBuffer = ByteBuffer.allocate(READ_BUFFER_CAPACITY_CLIENT)
        // Получаем текущее состояние
        val state = currentResponseState
            ?: run {
                logger.log(
                    Level.SEVERE,
                    "ApiClient: CRITICAL - No response state for read on connected channel. Recreating."
                )
                currentResponseState = SerializationUtils.ObjectReaderState() // Попытка восстановить
                return
            }

        val numRead: Int // Количество прочитанных байт

        try {
            numRead = socketChannel.read(tempBuffer) // Читаем и записываем кол-во байт прочитанных
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
            tempBuffer.flip() // Переключаем буфер из режима записи в режим чтения
            logger.log(Level.FINER, "ApiClient: Read $numRead bytes from server.")

            while (tempBuffer.hasRemaining()) {
                if (!state.isLengthRead) { // Если сообщение полностью не прочитано
                    // Пытаемся прочитать длину из текущего буфера
                    // SerializationUtils.ObjectReaderState.readLengthFromBuffer должен вернуть true, если длина полностью прочитана
                    if (state.readLengthFromBuffer(tempBuffer)) {
                        logger.log(Level.FINER, "ApiClient: Response length ${state.expectedLength} received.")
                    } else break
                }

                if (state.isLengthRead) {
                    // Пытаемся прочитать объект из текущего буфера
                    // SerializationUtils.ObjectReaderState.readObjectBytesFromBuffer должен вернуть true, если объект полностью прочитан
                    if (state.readObjectBytesFromBuffer(tempBuffer)) {
                        logger.log(Level.FINER, "ApiClient: Response object bytes received.")
                        val response = state.deserializeObject<Response>() // Десериализация из байт
                        if (response != null) {
                            synchronized(responseLock) {
                                lastReceivedResponse = response
                                responseAvailable.set(true)
                                responseLock.notifyAll() // Оповещаем основной поток, который мог ожидать ответа.
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
        synchronized(pendingRequests) { // Синхронизация для безопасного доступа к очереди
            val bufferToSend = pendingRequests.peek() // Смотрим, не удаляя
            if (bufferToSend == null) { // Очередь пуста
                key.interestOps(SelectionKey.OP_READ) // Больше не интересуемся записью
                return
            }

            try {
                // Пытаемся записать данные из буфера в канал
                // socketChannel.write() может записать не все данные из буфера за один раз
                val bytesWritten = socketChannel.write(bufferToSend)
                logger.log(
                    Level.FINER,
                    "ApiClient: Wrote $bytesWritten bytes of request. Remaining: ${bufferToSend.remaining()}"
                )
            } catch (e: IOException) {
                logger.log(Level.WARNING, "ApiClient: Error writing request to server: ${e.message}")
                handleDisconnect(key)
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
        channel = null
        connected.set(false)
        connectionPending.set(false)
        currentResponseState?.reset()

        // Сигнализируем ожидающему потоку, что произошла ошибка
        synchronized(responseLock) {
            lastReceivedResponse = null
            responseAvailable.set(true)
            responseLock.notifyAll()
        }

        // Очищаем очередь запросов при разрыве, т.к. их состояние неясно
        synchronized(pendingRequests) {
            pendingRequests.clear()
            logger.log(Level.WARNING, "ApiClient: Disconnected. ${pendingRequests.size} requests were in queue.")
        }

        // Пытаемся переподключиться через некоторое время
        if (running.get()) {
            logger.log(Level.INFO, "ApiClient: Scheduling reconnect attempt...")
            //Thread.sleep(10000)
            initiateConnection()
        }
    }

    // Публичный метод для отправки запроса и синхронного ожидания ответа.
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
        // Добавляем WRITE в фокусировку
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
            // Если responseAvailable.get() == true, значит, ответ (или ошибка) был получен
            return lastReceivedResponse // Возвращаем последний полученный ответ (может быть null, если была ошибка десериализации)
        }
    }

    // Метод для закрытия клиента правильного
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

    fun returnNewCommands(): MutableList<String> {
        synchronized(responseLock) {
            return lastReceivedResponse?.newCommandsList ?: mutableListOf()
        }
    }

    fun resetNewCommands() {
        synchronized(responseLock) {
            lastReceivedResponse = null
        }
    }
}
