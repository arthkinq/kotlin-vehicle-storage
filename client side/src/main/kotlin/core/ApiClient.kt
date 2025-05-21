package core

import common.CommandDescriptor
import common.Request
import common.Response
import common.SerializationUtils
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
        private const val RECONNECT_DELAY_MS = 5000L
        private const val SELECTOR_RECOVERY_DELAY_MS = 3000L
        private const val DEFAULT_REQUEST_TIMEOUT_MS = 10000L
        private const val SELECT_TIMEOUT_MS = 1000L
    }

    @Volatile // Используем Volatile для running тк он читается из разных потоков
    private var running = true

    private val connected = AtomicBoolean(false)
    private val connectionPending = AtomicBoolean(false)

    private val pendingRequests = ArrayDeque<ByteBuffer>()
    private var currentResponseState: SerializationUtils.ObjectReaderState? = null
    private var lastReceivedResponse: Response? = null
    private val responseLock = Object()
    private var responseAvailable = AtomicBoolean(false)

    private lateinit var nioThread: Thread
    private val logger = Logger.getLogger(ApiClient::class.java.name)

    var onCommandDescriptorsUpdated: ((List<CommandDescriptor>) -> Unit)? = null
    var onConnectionStatusChanged: ((Boolean, String?) -> Unit)? =
        null

    init {
        logger.level = Level.WARNING
        try {
            selector = Selector.open()
            nioThread = Thread { clientSelectorLoop() }.apply {
                name = "ClientNIOThread"
                start()
            }
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
        val changed = connected.getAndSet(isConnected) != isConnected
        if (changed || message != null) { // Вызываем callback если статус изменился или есть сообщение
            onConnectionStatusChanged?.invoke(isConnected, message)
        }
    }


    private fun initiateConnection() {
        if (!running || connected.get() || connectionPending.get()) {
            return
        }

        try {
            if (channel == null || channel?.isOpen == false) {
                channel?.closeQuietly()
                channel = SocketChannel.open()
                channel!!.configureBlocking(false)
                logger.log(Level.INFO, "ApiClient: New SocketChannel created for connection attempt.")
            }

            val currentSelector = selector
            if (currentSelector == null || !currentSelector.isOpen) {
                logger.log(
                    Level.WARNING,
                    "ApiClient: Selector not open during initiateConnection. Will be handled by selector loop."
                )
                connectionPending.set(false)
                return
            }

            connectionPending.set(true)
            updateConnectionStatus(false, "Attempting to connect to $serverHost:$serverPort...")
            logger.log(Level.INFO, "ApiClient: Initiating connection to $serverHost:$serverPort.")

            val connectedImmediately = channel!!.connect(InetSocketAddress(serverHost, serverPort))

            if (connectedImmediately) {
                logger.log(Level.INFO, "ApiClient: Connected immediately.")
                completeConnectionSetup(null)
            } else {
                logger.log(Level.INFO, "ApiClient: Connection pending.")
                channel!!.register(currentSelector, SelectionKey.OP_CONNECT)
            }
            currentSelector.wakeup()
        } catch (e: ConnectException) {
            connectionPending.set(false)
            val errorMsg = "Connection refused to $serverHost:$serverPort. Will retry."
            logger.log(Level.WARNING, "$errorMsg Error: ${e.message}")
            updateConnectionStatus(false, errorMsg)
            channel?.closeQuietly()
            channel = null
        } catch (e: ClosedChannelException) {
            connectionPending.set(false)
            val errorMsg = "Channel closed during connection attempt. Will retry."
            logger.log(Level.WARNING, "$errorMsg Error: ${e.message}")
            updateConnectionStatus(false, errorMsg)
            channel = null
        } catch (e: Exception) {
            connectionPending.set(false)
            val errorMsg = "Error initiating connection. Will retry."
            logger.log(Level.WARNING, "$errorMsg Error: ${e.message}", e)
            updateConnectionStatus(false, errorMsg)
            channel?.closeQuietly()
            channel = null
        }
    }

    private fun SocketChannel.closeQuietly() {
        try {
            if (isOpen) this.close()
        } catch (e: IOException) { // ignore
        }
    }

    private fun completeConnectionSetup(key: SelectionKey?) {
        connectionPending.set(false)
        currentResponseState = SerializationUtils.ObjectReaderState()
        updateConnectionStatus(true, "Successfully connected to server.")
        logger.log(Level.INFO, "ApiClient: Connection to server complete.")

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

                if (existingKey == null || !existingKey.isValid) { // Если это новое подключение или ключ невалиден
                    currentChannel.register(currentSelector, newOps)
                } else {
                    if ((existingKey.interestOps() and newOps) != newOps) { // Обновляем только если отличается
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
        var lastReconnectAttemptTime = 0L
        var lastSelectorRecoveryAttemptTime =
            System.currentTimeMillis()

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
                            updateConnectionStatus(false, "Selector recovered; attempting to reconnect.")
                            connectionPending.set(false)
                            channel?.closeQuietly()
                            channel = null
                        } catch (e: IOException) {
                            logger.log(
                                Level.SEVERE,
                                "ApiClient: Failed to re-open selector. Client networking is likely non-functional.",
                                e
                            )
                            running = false // Критическая ошибка, если селектор не восстановить
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
                if (!connected.get() && !connectionPending.get()) {
                    if (System.currentTimeMillis() - lastReconnectAttemptTime > RECONNECT_DELAY_MS) {
                        initiateConnection()
                        lastReconnectAttemptTime = System.currentTimeMillis()
                    }
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
                running = false // Останавливаем при неизвестной критической ошибке
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
                logger.log(Level.INFO, "ApiClient: Successfully connected via finishConnect().")
                completeConnectionSetup(key)
            }
        } catch (e: ConnectException) {
            val errorMsg = "Connection failed in finishConnect(): ${e.message}"
            logger.log(Level.WARNING, "ApiClient: $errorMsg")
            updateConnectionStatus(false, errorMsg)
            connectionPending.set(false)
            key.cancel()
            socketChannel.closeQuietly()
            channel = null
        } catch (e: IOException) { // Другие IOException
            val errorMsg = "IOException during finishConnect(): ${e.message}"
            logger.log(Level.WARNING, "ApiClient: $errorMsg")
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
                            if (response.commandDescriptors.isNotEmpty()) {
                                onCommandDescriptorsUpdated?.invoke(response.commandDescriptors)
                                logger.log(
                                    Level.INFO,
                                    "ApiClient: Command descriptors updated. Count: ${response.commandDescriptors.size}"
                                )
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
        updateConnectionStatus(false, disconnectMessage)

        key?.cancel()
        channel?.closeQuietly()
        channel = null
        connectionPending.set(false)
        currentResponseState?.reset()

        synchronized(responseLock) {
            lastReceivedResponse = null
            responseAvailable.set(true)
            responseLock.notifyAll()
        }
        logger.log(Level.WARNING, "ApiClient: Disconnected. ${pendingRequests.size} requests remain in queue.")
        selector?.wakeup()
    }

    fun sendRequestAndWaitForResponse(request: Request, timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS): Response? {
        if (!running) {
            ioManager.error("ApiClient is not running. Cannot send request.")
            return null
        }

        if (!connected.get()) {
            ioManager.outputLine("ApiClient: Not currently connected. Request will be queued.")
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
        // connected и connectionPending уже должны быть false
        synchronized(pendingRequests) {
            pendingRequests.clear()
        }
        logger.log(Level.INFO, "ApiClient: Resources cleaned up.")
    }

}