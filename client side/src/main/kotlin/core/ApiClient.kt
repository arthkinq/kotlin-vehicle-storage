package core

import common.CommandDescriptor
import common.Request
import common.Response
import common.SerializationUtils
import javafx.application.Platform
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.*
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger
import myio.IOManager

class ApiClient(
    private val ioManager: IOManager,
    private val serverHost: String = "localhost",
    private val serverPort: Int = 8888
) {
    private var selector: Selector? = null
    private var channel: SocketChannel? = null

    companion object {
        private const val READ_BUFFER_CAPACITY_CLIENT = 4096
        private const val SELECTOR_RECOVERY_DELAY_MS = 3000L
        private const val DEFAULT_REQUEST_TIMEOUT_MS = 10000L
        private const val SELECT_TIMEOUT_MS = 1000L
        private const val CONNECT_ATTEMPT_TIMEOUT_MS = 5000L
    }




    private var currentSessionUsername: String? = null
    private var currentSessionPassword: String? = null
    private val sessionLock = Any()

    @Volatile
    private var running = true

    private val connected = AtomicBoolean(false)
    private val connectionPending = AtomicBoolean(false)

    private val pendingRequests = ArrayDeque<ByteBuffer>()
    private var currentResponseState: SerializationUtils.ObjectReaderState? = null
    private var lastReceivedResponse: Response? = null
    private val responseLock = Object() // Для sendRequestAndWaitForResponse И для connectIfNeeded
    private var responseAvailable = AtomicBoolean(false)

    private lateinit var nioThread: Thread
    private val logger = Logger.getLogger(ApiClient::class.java.name)


    private var lastKnownCommandDescriptors: List<CommandDescriptor>? = null // Кэш
    private val descriptorsLock = Any() // Лок для кэша

    var onCommandDescriptorsUpdated: ((List<CommandDescriptor>) -> Unit)? = null
    var onConnectionStatusChanged: ((Boolean, String?) -> Unit)? = null

    init {
        logger.level = Level.INFO
        try {
            selector = Selector.open()
            nioThread = Thread { clientSelectorLoop() }.apply {
                name = "ClientNIOThread"
                start()
            }
            Thread { connectIfNeeded(CONNECT_ATTEMPT_TIMEOUT_MS * 2) }.start()
        } catch (e: IOException) {
            logger.log(Level.SEVERE, "ApiClient: CRITICAL - Failed to open Selector during initialization.", e)
            ioManager.error("ApiClient: Critical error during initialization: ${e.message}. Client will not be able to connect.")
            running = false
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "ApiClient: CRITICAL - Unexpected error during initialization.", e)
            ioManager.error("ApiClient: Unexpected critical error during initialization: ${e.message}. Client may not function.")
            running = false
        }
    }

    private fun updateConnectionStatus(isConnected: Boolean, message: String? = null) {
        val oldStatus = connected.get()
        connected.set(isConnected)
        val changed = oldStatus != isConnected

        if (changed || message != null) {
            Platform.runLater {
                onConnectionStatusChanged?.invoke(isConnected, message)
            }
        }

        if (changed || !connectionPending.get()) { // Если статус изменился ИЛИ pending сброшен
            synchronized(responseLock) { responseLock.notifyAll() }
        }
    }

    fun setCurrentUserCredentials(username: String?, password: String?) {
        synchronized(sessionLock) {
            this.currentSessionUsername = username
            this.currentSessionPassword = password
            logger.info(if (username == null) "ApiClient: User credentials cleared." else "ApiClient: User credentials set for '$username'.")
        }
    }

    fun getCurrentUserCredentials(): Pair<String, String>? {
        synchronized(sessionLock) {
            val u = currentSessionUsername
            val p = currentSessionPassword
            return if (u != null && p != null) Pair(u, p) else null
        }
    }

    fun clearCurrentUserCredentials() {
        synchronized(sessionLock) { /* ... */ }
        synchronized(descriptorsLock) { // Очищаем команды при логауте
            lastKnownCommandDescriptors = null
        }
        // Уведомляем, что команды "сброшены" (стали пустыми)
        Platform.runLater {
            onCommandDescriptorsUpdated?.invoke(emptyList())
        }
    }

    @Synchronized
    fun connectIfNeeded(timeoutMs: Long = CONNECT_ATTEMPT_TIMEOUT_MS): Boolean {
        if (!running) {
            ioManager.error("ApiClient is not running. Cannot connect.")
            return false
        }
        if (connected.get()) {
            return true
        }

        if (connectionPending.get()) {
            logger.info("connectIfNeeded: Connection attempt already in progress. Waiting...")
            val waitStartTime = System.currentTimeMillis()
            synchronized(responseLock) {
                while (connectionPending.get() && !connected.get() && (System.currentTimeMillis() - waitStartTime < timeoutMs)) {
                    try {
                        responseLock.wait(200)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt(); return false
                    }
                }
            }
            logger.info("connectIfNeeded: Wait for pending connection finished. Connected: ${connected.get()}")
            return connected.get()
        }

        connectionPending.set(true)
        updateConnectionStatus(false, "Attempting to connect to $serverHost:$serverPort...")
        initiateConnectionInternal()

        val startTime = System.currentTimeMillis()
        var connectionEstablishedDuringWait = false
        synchronized(responseLock) {
            while (connectionPending.get() && !connected.get() && (System.currentTimeMillis() - startTime < timeoutMs)) {
                try {
                    val timeLeft = timeoutMs - (System.currentTimeMillis() - startTime)
                    if (timeLeft <= 0) break
                    responseLock.wait(timeLeft.coerceAtLeast(50L)) // Ждем оставшееся время
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    updateConnectionStatus(false, "Connection attempt interrupted.")
                    connectionPending.set(false)
                    return false
                }
            }
            connectionEstablishedDuringWait = connected.get()
        }


        if (!connectionEstablishedDuringWait && connectionPending.get()) {
            connectionPending.set(false)
            updateConnectionStatus(false, "Failed to connect to server within timeout (connectIfNeeded).")
        }
        logger.info("connectIfNeeded finished. Connected: $connectionEstablishedDuringWait")
        return connectionEstablishedDuringWait
    }

    private fun initiateConnectionInternal() {
        // Эта функция вызывается из connectIfNeeded (который установил connectionPending = true)
        // или из clientSelectorLoop (для первоначальной попытки, если нужно)
        try {
            if (channel == null || channel?.isOpen == false) {
                channel?.closeQuietly()
                channel = SocketChannel.open()
                channel!!.configureBlocking(false)
                logger.info("ApiClient: New SocketChannel created.")
            }

            val currentSelector = selector
            if (currentSelector == null || !currentSelector.isOpen) {
                logger.warning("ApiClient: Selector not open during internal connection initiation. Aborting.")
                connectionPending.set(false) // Попытка провалилась
                updateConnectionStatus(false, "Internal error: Selector not available.")
                synchronized(responseLock) { responseLock.notifyAll() } // Разбудить connectIfNeeded
                return
            }
            logger.info("ApiClient: Initiating connection (internal) to $serverHost:$serverPort.")
            val connectedImmediately = channel!!.connect(InetSocketAddress(serverHost, serverPort))

            if (connectedImmediately) {
                logger.info("ApiClient: Connected immediately (internal).")
                completeConnectionSetup(null) // Это вызовет updateConnectionStatus, сбросит pending, разбудит lock
            } else {
                logger.info("ApiClient: Connection pending (internal).")
                channel!!.register(currentSelector, SelectionKey.OP_CONNECT) // nioThread обработает OP_CONNECT
            }
            currentSelector.wakeup()
        } catch (e: Exception) {
            val errorMsg = "Error during internal connection initiation: ${e.message}"
            logger.log(Level.WARNING, "ApiClient: $errorMsg", e)
            updateConnectionStatus(false, errorMsg)
            connectionPending.set(false)
            channel?.closeQuietly(); channel = null
            synchronized(responseLock) { responseLock.notifyAll() }
        }
    }

    private fun SocketChannel.closeQuietly() {
        try {
            if (isOpen) this.close()
        } catch (_: IOException) {
        }
    }

    private fun completeConnectionSetup(key: SelectionKey?) {
        currentResponseState = SerializationUtils.ObjectReaderState()
        // updateConnectionStatus(true, ...) вызывается первым, чтобы connected.get() был актуален для requestCommand...
        updateConnectionStatus(true, "Successfully connected to server.")
        connectionPending.set(false) // Явно сбрасываем здесь
        logger.info("ApiClient: Connection to server complete.")
        synchronized(responseLock) { responseLock.notifyAll() }

        requestCommandDescriptorsUpdate()

        var newOps = SelectionKey.OP_READ
        synchronized(pendingRequests) {
            if (pendingRequests.isNotEmpty()) {
                newOps = newOps or SelectionKey.OP_WRITE
            }
        }

        try {
            val currentChannel = this.channel
            val currentSelector = this.selector
            if (currentChannel != null && currentChannel.isOpen && currentSelector != null && currentSelector.isOpen) {
                val existingKey = if (key?.isValid == true) key else currentChannel.keyFor(currentSelector)

                if (existingKey == null || !existingKey.isValid) {
                    currentChannel.register(currentSelector, newOps)
                } else {
                    if ((existingKey.interestOps() and newOps) != newOps) {
                        existingKey.interestOps(newOps)
                    }
                }
                currentSelector.wakeup()
            } else {
                logger.log(
                    Level.WARNING, "ApiClient: Cannot complete setup post-connect, channel or selector not ready."
                )
                handleDisconnect(key, "Channel or selector not ready post-connect")
            }
        } catch (e: ClosedChannelException) {
            logger.log(Level.WARNING, "ApiClient: Channel closed during post-connect setup. Error: ${e.message}")
            handleDisconnect(key, "Channel closed during post-connect setup")
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "ApiClient: Error in post-connect setup. Error: ${e.message}", e)
            handleDisconnect(key, "Error in post-connect setup")
        }
    }

    private fun requestCommandDescriptorsUpdate() {
        val currentSel = selector
        val currentChan = channel
        if (!connected.get() || currentChan == null || !currentChan.isOpen || currentSel == null || !currentSel.isOpen) {
            logger.log(Level.WARNING, "ApiClient: Cannot request command descriptors, preconditions not met.")
            return
        }
        val helpRequest = Request(body = listOf("help"), vehicle = null)
        val requestBuffer = SerializationUtils.objectToByteBuffer(helpRequest)
        logger.log(Level.INFO, "ApiClient: Queueing 'help' request to update command list.")
        synchronized(pendingRequests) {
            pendingRequests.addFirst(requestBuffer)
        }
        currentChan.keyFor(currentSel)?.let { key ->
            if (key.isValid && (key.interestOps() and SelectionKey.OP_WRITE) == 0) {
                key.interestOps(key.interestOps() or SelectionKey.OP_WRITE)
            }
        }
        currentSel.wakeup()
    }

    private fun clientSelectorLoop() {
        logger.log(Level.INFO, "ApiClient: NIO event loop started.")
        var lastSelectorRecoveryAttemptTime = System.currentTimeMillis()


        while (running) {
            try {
                var currentSelector = selector
                if (currentSelector == null || !currentSelector.isOpen) {
                    if (System.currentTimeMillis() - lastSelectorRecoveryAttemptTime > SELECTOR_RECOVERY_DELAY_MS) {
                        logger.log(
                            Level.WARNING, "ApiClient: Selector is null or closed. Attempting to recover selector."
                        )
                        lastSelectorRecoveryAttemptTime = System.currentTimeMillis()
                        try {
                            selector?.close()
                            selector = Selector.open()
                            currentSelector = selector
                            logger.log(Level.INFO, "ApiClient: Selector re-opened.")
                            updateConnectionStatus(
                                false,
                                "Selector recovered; connection needs to be re-established manually."
                            )
                            connectionPending.set(false)
                            channel?.closeQuietly(); channel = null
                        } catch (e: IOException) {
                            logger.log(
                                Level.SEVERE,
                                "ApiClient: Failed to re-open selector. Client networking is likely non-functional.",
                                e
                            )
                            running = false
                            updateConnectionStatus(false, "CRITICAL: Failed to recover selector. Client stopping.")
                            break
                        }
                    } else {
                        Thread.sleep(SELECT_TIMEOUT_MS)
                        continue
                    }
                }
                val selectorToUse = currentSelector
                if (selectorToUse == null || !selectorToUse.isOpen) {
                    logger.log(
                        Level.WARNING, "ApiClient: Selector still not usable after recovery attempt. Skipping cycle."
                    )
                    Thread.sleep(SELECT_TIMEOUT_MS)
                    continue
                }

                val readyCount = selectorToUse.select(SELECT_TIMEOUT_MS)

                if (!running) break
                if (readyCount == 0) continue

                val selectedKeys = selectorToUse.selectedKeys().iterator()
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
                    } catch (e: IOException) {
                        val errorMsg = "IOException on channel ${key.channel()}: ${e.message}."
                        logger.log(Level.WARNING, "ApiClient: $errorMsg")
                        handleDisconnect(key, errorMsg)
                    } catch (e: Exception) {
                        val errorMsg = "Error processing key for channel ${key.channel()}: ${e.message}"
                        logger.log(Level.SEVERE, "ApiClient: $errorMsg", e)
                        handleDisconnect(key, errorMsg)
                    }
                }
            } catch (e: ClosedSelectorException) {
                logger.log(Level.WARNING, "ApiClient: Selector closed during select. Will attempt recovery.")
                selector = null
                updateConnectionStatus(false, "Selector closed; attempting recovery.")
            } catch (e: IOException) {
                logger.log(Level.WARNING, "ApiClient: IOException in selector loop (select() failed?): ${e.message}", e)
                if (selector?.isOpen == false) selector = null
                Thread.sleep(SELECT_TIMEOUT_MS)
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "ApiClient: Unexpected critical error in selector loop: ${e.message}", e)
                running = false
                updateConnectionStatus(false, "CRITICAL: Unexpected error in network loop. Client stopping.")
                break
            }
        }
        cleanupResources()
        logger.log(Level.INFO, "ApiClient: NIO event loop finished and resources cleaned.")
        updateConnectionStatus(false, "Client stopped.")
    }

    private fun handleConnect(key: SelectionKey) {
        val socketChannel = key.channel() as SocketChannel
        try {
            if (socketChannel.finishConnect()) {
                logger.info("ApiClient: Successfully connected via finishConnect().")
                completeConnectionSetup(key) // Установит connected=true, сбросит pending, разбудит lock
            }
            // Если finishConnect() == false, ключ остается на OP_CONNECT. connectionPending остается true.
            // connectIfNeeded будет ждать.
        } catch (e: ConnectException) {
            val errorMsg = "Connection failed in finishConnect(): ${e.message}"
            logger.log(Level.WARNING, "ApiClient: $errorMsg") // Логгируем, но не дублируем сообщение в ioManager
            updateConnectionStatus(false, errorMsg) // Обновит статус и вызовет callback
            connectionPending.set(false) // Попытка завершена неудачно
            key.cancel(); socketChannel.closeQuietly(); channel = null
            synchronized(responseLock) { responseLock.notifyAll() } // Разбудить connectIfNeeded
        } catch (e: IOException) {
            val errorMsg = "IOException during finishConnect(): ${e.message}"
            logger.log(Level.WARNING, "ApiClient: $errorMsg")
            // handleDisconnect установит connected=false, сбросит pending, разбудит lock
            handleDisconnect(key, errorMsg)
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
            val errorMsg = "Error reading from server: ${e.message}"
            logger.log(Level.WARNING, "ApiClient: $errorMsg")
            handleDisconnect(key, errorMsg)
            return
        }

        if (numRead == -1) {
            val errorMsg = "Server closed connection."
            logger.log(Level.INFO, "ApiClient: $errorMsg")
            handleDisconnect(key, errorMsg)
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
                            logger.info("ApiClient: Received response. Text: '${response.responseText.take(30)}...', Descriptors count: ${response.commandDescriptors.size}") // <--- ДОБАВЬ ЭТОТ ЛОГ
                            if (response.commandDescriptors.isNotEmpty()) {
                                synchronized(descriptorsLock) { // Защищаем доступ к кэшу
                                    lastKnownCommandDescriptors = response.commandDescriptors
                                }
                                Platform.runLater { // Убедимся, что callback в UI потоке
                                    onCommandDescriptorsUpdated?.invoke(response.commandDescriptors)
                                }
                                logger.log(Level.INFO, "ApiClient: Command descriptors updated. Count: ${response.commandDescriptors.size}")
                            }
                            synchronized(responseLock) {
                                lastReceivedResponse = response
                                responseAvailable.set(true)
                                responseLock.notifyAll()
                            }
                            logger.log(
                                Level.INFO, "ApiClient: Response processed: ${response.responseText.take(50)}..."
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

    fun getCachedCommandDescriptors(): List<CommandDescriptor>? {
        synchronized(descriptorsLock) {
            return lastKnownCommandDescriptors
        }
    }

    private fun handleWrite(key: SelectionKey) {
        val socketChannel = key.channel() as SocketChannel
        synchronized(pendingRequests) {
            val bufferToSend = pendingRequests.peekFirst()
            if (bufferToSend == null) {
                if (key.isValid) key.interestOps(SelectionKey.OP_READ)
                return
            }
            try {
                val bytesWritten = socketChannel.write(bufferToSend)
                logger.log(Level.FINER, "ApiClient: Wrote $bytesWritten bytes. Remaining: ${bufferToSend.remaining()}")
            } catch (e: IOException) {
                val errorMsg = "Error writing request: ${e.message}"
                logger.log(Level.WARNING, "ApiClient: $errorMsg")
                handleDisconnect(key, errorMsg)
                return
            }
            if (!bufferToSend.hasRemaining()) {
                pendingRequests.pollFirst()
                logger.log(Level.INFO, "ApiClient: Request sent completely.")
            }
            if (pendingRequests.isEmpty()) {
                if (key.isValid) key.interestOps(SelectionKey.OP_READ)
            }
        }
    }

    private fun handleDisconnect(key: SelectionKey?, reason: String?) {
        val disconnectMessage = "Handling disconnect. Reason: ${reason ?: "Unknown"}"
        logger.log(Level.INFO, "ApiClient: $disconnectMessage")
        updateConnectionStatus(false, disconnectMessage) // Установит connected = false

        // НЕ СБРАСЫВАЕМ КРЕДЫ АВТОМАТИЧЕСКИ ЗДЕСЬ
        // apiClient.clearCurrentUserCredentials() // Это будет делать MainController при необходимости

        key?.cancel()
        channel?.closeQuietly(); channel = null
        connectionPending.set(false) // Важно сбросить, если был pending
        currentResponseState?.reset()

        synchronized(responseLock) {
            lastReceivedResponse = null
            responseAvailable.set(true)
            responseLock.notifyAll() // Разбудить sendRequestAndWaitForResponse И connectIfNeeded
        }
        logger.warning("ApiClient: Disconnected. ${pendingRequests.size} requests remain in queue.")
        selector?.wakeup()
    }

    fun sendRequestAndWaitForResponse(request: Request, timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS): Response? {
        if (!running) {
            ioManager.error("ApiClient is not running. Cannot send request.")
            return null
        }

        if (!connected.get()) {
            ioManager.error("ApiClient: Not connected. Command may not be sent if connection is not established prior to next command.")
        }

        val requestBuffer = SerializationUtils.objectToByteBuffer(request)
        synchronized(responseLock) {
            lastReceivedResponse = null
            responseAvailable.set(false)
        }
        synchronized(pendingRequests) {
            pendingRequests.addLast(requestBuffer)
        }

        if (connected.get() && channel != null && channel!!.isOpen && selector != null && selector!!.isOpen) {
            channel?.keyFor(selector)?.let { key ->
                if (key.isValid && (key.interestOps() and SelectionKey.OP_WRITE) == 0) {
                    try {
                        key.interestOps(key.interestOps() or SelectionKey.OP_WRITE)
                    } catch (e: CancelledKeyException) {
                        logger.warning("Key cancelled while trying to set OP_WRITE. Disconnect likely.")
                        handleDisconnect(key, "Key cancelled for OP_WRITE")
                    }
                }
            }
            selector?.wakeup()
        }

        val overallStartTime = System.currentTimeMillis()
        synchronized(responseLock) {
            while (!responseAvailable.get() && running && (System.currentTimeMillis() - overallStartTime < timeoutMs)) {
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
                val statusMsg =
                    if (!connected.get()) "Disconnected while waiting or request not sent." else "Timeout waiting for server response."
                ioManager.error("ApiClient: $statusMsg")
                return null
            }
            return lastReceivedResponse
        }
    }

    fun close() {
        if (!running) {
            logger.log(Level.INFO, "ApiClient: Close called, but already shutting down or stopped.")
            return
        }
        logger.log(Level.INFO, "ApiClient: Initiating shutdown by user command.")
        running = false
        selector?.wakeup()
        try {
            if (Thread.currentThread() != nioThread) {
                nioThread.join(3000)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.log(Level.WARNING, "ApiClient: Interrupted while waiting for NIO thread to join.")
        }
        logger.log(Level.INFO, "ApiClient: Shutdown sequence complete from close().")
    }

    private fun cleanupResources() {
        logger.log(Level.INFO, "ApiClient: Cleaning up resources...")
        channel?.closeQuietly()
        selector?.let { sel ->
            try {
                if (sel.isOpen) sel.close()
            } catch (e: IOException) {
                logger.log(Level.WARNING, "ApiClient: Error closing selector: ${e.message}", e)
            }
        }
        channel = null
        selector = null
        synchronized(pendingRequests) {
            pendingRequests.clear()
        }
        logger.log(Level.INFO, "ApiClient: Resources cleaned up.")
    }

    fun isConnected() = connected.get()
    fun isConnectionPending() = connectionPending.get() // Добавлено для LoginController
    fun isRunning() = running
}