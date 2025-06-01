package core

import myio.IOManager
import common.Request
import common.Response // Ensure Response is imported
import common.SerializationUtils
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ForkJoinPool // Import ForkJoinPool
import java.util.logging.Level
import java.util.logging.Logger

class ApiServer(
    private val commandProcessor: CommandProcessor, // commandProcessor now takes fewer args
    private val ioManager: IOManager
) {
    private val logger = Logger.getLogger(ApiServer::class.java.name)

    companion object {
        private const val DEFAULT_PORT = 8888
        private const val READ_BUFFER_CAPACITY = 8192 // Increased buffer slightly
    }

    @Volatile
    private var selector: Selector? = null
    @Volatile
    private var serverSocketChannel: ServerSocketChannel? = null

    private val clientReadStates = ConcurrentHashMap<SocketChannel, SerializationUtils.ObjectReaderState>()
    private val clientWriteQueues = ConcurrentHashMap<SocketChannel, ArrayDeque<ByteBuffer>>() // Queue of ByteBuffers to send

    private val requestProcessingPool = ForkJoinPool(Runtime.getRuntime().availableProcessors())
    private val responsePreparationPool = ForkJoinPool(Runtime.getRuntime().availableProcessors())


    @Volatile
    private var running = false

    fun startServer(port: Int = DEFAULT_PORT) {
        logger.log(Level.INFO, "NIO TCP Server is preparing to start on port $port.")
        running = true

        try {
            // Initialize DatabaseManager (it has an init block that sets up tables)
            db.DatabaseManager.getConnection().use { /* Just to trigger init */ }
            logger.info("Database initialized/checked.")

            selector = Selector.open()
            serverSocketChannel = ServerSocketChannel.open()
            serverSocketChannel!!.configureBlocking(false)
            serverSocketChannel!!.socket().bind(InetSocketAddress(port))
            serverSocketChannel!!.register(selector, SelectionKey.OP_ACCEPT)

            logger.log(Level.INFO, "Server started. Listening on ${serverSocketChannel!!.localAddress}")

            Thread { adminConsoleLoop() }.apply {
                name = "AdminConsoleThread"
                isDaemon = true
                start()
            }
            logger.log(Level.INFO, "Admin console input thread started.")

            while (running && serverSocketChannel!!.isOpen && selector!!.isOpen) {
                try {
                    selector!!.select(1000)
                    if (!running) break

                    val selectedKeys = selector!!.selectedKeys().iterator()
                    while (selectedKeys.hasNext()) {
                        val key = selectedKeys.next()
                        selectedKeys.remove()
                        if (!key.isValid) continue

                        try {
                            when {
                                key.isAcceptable -> handleAccept(key)
                                key.isReadable -> handleRead(key) // This will now offload processing
                                key.isWritable -> handleWrite(key)
                            }
                        } catch (e: CancelledKeyException) {
                            logger.log(Level.FINER, "Key cancelled for ${key.channel()}", e)
                            cleanupClient(key.channel() as? SocketChannel, key)
                        } catch (e: IOException) {
                            logger.log(Level.WARNING, "IOException on channel ${key.channel()}: ${e.message}.")
                            cleanupClient(key.channel() as? SocketChannel, key)
                        } catch (e: Exception) {
                            logger.log(Level.SEVERE, "Error processing key for channel ${key.channel()}: ${e.message}", e)
                            cleanupClient(key.channel() as? SocketChannel, key)
                        }
                    }
                } catch (e: ClosedSelectorException) {
                    logger.log(Level.INFO, "Selector closed, server shutting down.")
                    running = false
                } catch (e: IOException) {
                    if (selector?.isOpen == false) {
                        logger.log(Level.INFO, "Selector closed during select (IOException), shutting down.")
                        running = false
                    } else {
                        logger.log(Level.WARNING, "IOException in select loop: ${e.message}", e)
                    }
                } catch (e: Exception) {
                    logger.log(Level.SEVERE, "Unexpected error in server select loop: ${e.message}", e)
                    running = false
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
            clientWriteQueues[clientChannel] = ArrayDeque() // Initialize write queue
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
            logger.log(Level.INFO, "Client ${clientChannel.remoteAddress} likely closed connection during read: ${e.message}")
            cleanupClient(clientChannel, key)
            return
        }

        if (numRead == -1) {
            logger.log(Level.INFO, "Client ${clientChannel.remoteAddress} has closed the channel gracefully.")
            cleanupClient(clientChannel, key)
            return
        }

        if (numRead > 0) {
            readBuffer.flip()
            while (readBuffer.hasRemaining()) {
                if (!readState.isLengthRead) {
                    if (!readState.readLengthFromBuffer(readBuffer)) break
                }
                if (readState.isLengthRead) {
                    if (readState.readObjectBytesFromBuffer(readBuffer)) {
                        val request: Request? = try {
                            readState.deserializeObject()
                        } catch (e: Exception) {
                            logger.log(Level.WARNING, "Deserialization error from ${clientChannel.remoteAddress}: ${e.message}", e)
                            null // Or send error response
                        } finally {
                            readState.reset()
                        }

                        if (request != null) {
                            logger.log(Level.INFO, "Received request from ${clientChannel.remoteAddress}: command='${request.body.getOrNull(0)}'")
                            // Offload request processing to ForkJoinPool
                            requestProcessingPool.execute {
                                val response = commandProcessor.processCommand(request)
                                response.clearCommandDescriptors() // Always send fresh descriptors
                                response.addCommandDescriptors(commandProcessor.getCommandDescriptors())

                                // Offload response serialization to another ForkJoinPool
                                responsePreparationPool.execute {
                                    val responseBuffer = SerializationUtils.objectToByteBuffer(response)
                                    queueResponseBuffer(clientChannel, responseBuffer, key)
                                }
                            }
                        } else {
                            logger.log(Level.WARNING, "Failed to deserialize request from ${clientChannel.remoteAddress}. Invalid data format.")
                            // Optionally, send an error response back
                            val errorResponse = Response("Error: Invalid request format received by server.")
                            errorResponse.addCommandDescriptors(commandProcessor.getCommandDescriptors())
                            responsePreparationPool.execute {
                                val responseBuffer = SerializationUtils.objectToByteBuffer(errorResponse)
                                queueResponseBuffer(clientChannel, responseBuffer, key)
                            }
                        }
                        // readState.reset() // Moved into try-finally
                    } else break
                }
            }
        }
    }

    private fun queueResponseBuffer(clientChannel: SocketChannel, buffer: ByteBuffer, key: SelectionKey) {
        clientWriteQueues[clientChannel]?.let { queue ->
            synchronized(queue) { // Synchronize access to the specific client's queue
                queue.addLast(buffer)
            }
            // Ensure OP_WRITE is set. This needs to be done carefully to avoid race conditions
            // with the selector thread. Waking up the selector is key.
            if (key.isValid && (key.interestOps() and SelectionKey.OP_WRITE) == 0) {
                try {
                    key.interestOps(key.interestOps() or SelectionKey.OP_WRITE)
                } catch (cke: CancelledKeyException) {
                    logger.finer("Key cancelled before setting OP_WRITE for ${clientChannel.remoteAddress}")
                }
            }
            selector?.wakeup() // Wake up selector thread to process OP_WRITE
        } ?: logger.warning("No write queue for client ${clientChannel.remoteAddress} when trying to queue response.")
    }


    private fun handleWrite(key: SelectionKey) {
        val clientChannel = key.channel() as SocketChannel
        val queue = clientWriteQueues[clientChannel] ?: return // No queue, nothing to write

        synchronized(queue) { // Synchronize access to this client's queue
            val bufferToWrite = queue.firstOrNull()
            if (bufferToWrite == null) { // Queue is empty
                if (key.isValid) key.interestOps(SelectionKey.OP_READ)
                return
            }

            try {
                val bytesWritten = clientChannel.write(bufferToWrite)
                logger.log(Level.FINER, "Wrote $bytesWritten bytes of pending data to ${clientChannel.remoteAddress}. Remaining in buffer: ${bufferToWrite.remaining()}")
            } catch (e: IOException) {
                logger.log(Level.WARNING, "IOException during write to ${clientChannel.remoteAddress}: ${e.message}")
                cleanupClient(clientChannel, key)
                return
            }

            if (!bufferToWrite.hasRemaining()) {
                queue.removeFirstOrNull() // Remove fully sent buffer
                logger.log(Level.INFO, "Finished writing a response buffer to ${clientChannel.remoteAddress}.")
            }

            if (queue.isEmpty()) { // If queue is now empty, remove OP_WRITE interest
                if (key.isValid) key.interestOps(SelectionKey.OP_READ)
            }
            // If queue still has data, OP_WRITE remains, selector will call handleWrite again
        }
    }

    private fun cleanupClient(clientChannel: SocketChannel?, key: SelectionKey?) {
        if (clientChannel == null) return
        val remoteAddr = try { clientChannel.remoteAddress } catch (e: Exception) { "N/A" }
        logger.log(Level.INFO, "Cleaning up client $remoteAddr")

        clientReadStates.remove(clientChannel)
        clientWriteQueues.remove(clientChannel) // Remove write queue

        try {
            clientChannel.close()
        } catch (e: IOException) {
            logger.log(Level.WARNING, "Error closing client channel $remoteAddr during cleanup: ${e.message}")
        }
        key?.cancel()
    }

    private fun adminConsoleLoop() {
        logger.log(Level.INFO, "AdminConsoleThread: Started.")
        // Removed "save" commands as per requirements.
        // Admin console can be used for "exit" or other server-side only commands in future.
        try {
            while (running) {
                if (!System.`in`.bufferedReader().ready() && !running) break
                val serverAdminCommand = ioManager.readLine()
                if (!running && (serverAdminCommand == null || serverAdminCommand.isBlank())) break

                logger.log(Level.FINER, "AdminConsoleThread: Received command: '$serverAdminCommand'")

                when (serverAdminCommand?.trim()?.lowercase()) {
                    "exitadmin", "exit" -> { // Added "exit" for convenience
                        logger.log(Level.INFO, "AdminConsoleThread: Processing 'exit'.")
                        running = false
                        selector?.wakeup() // Wake up selector to notice 'running' flag
                        logger.log(Level.INFO, "AdminConsoleThread: Server shutdown initiated.")
                        return
                    }
                    // "saveadmin" is removed
                    else -> {
                        if (!serverAdminCommand.isNullOrBlank()) {
                            logger.log(Level.INFO, "AdminConsoleThread: Unknown command: '$serverAdminCommand'")
                        }
                    }
                }
                if (!running) break // Check after processing
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.log(Level.INFO, "AdminConsoleThread: Interrupted.")
        } catch (e: IOException) {
            // ... (rest of existing catch block)
        } catch (e: Exception) {
            // ... (rest of existing catch block)
        } finally {
            logger.log(Level.INFO, "AdminConsoleThread: Finished.")
        }
    }

    private fun shutdownServerInternals() {
        logger.log(Level.INFO, "Shutting down server internals...")
        running = false // Ensure it's set

        // Shutdown ForkJoinPools
        requestProcessingPool.shutdown()
        responsePreparationPool.shutdown()
        try {
            if (!requestProcessingPool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                requestProcessingPool.shutdownNow()
            }
            if (!responsePreparationPool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                responsePreparationPool.shutdownNow()
            }
        } catch (ie: InterruptedException) {
            requestProcessingPool.shutdownNow()
            responsePreparationPool.shutdownNow()
            Thread.currentThread().interrupt()
        }
        logger.log(Level.INFO, "ForkJoinPools shutdown.")


        selector?.let {
            if (it.isOpen) {
                it.keys().forEach { key ->
                    try { key.channel()?.close() } catch (e: IOException) { /* ignore */ }
                    key.cancel()
                }
                try { it.close() } catch (e: IOException) { logger.log(Level.WARNING, "Error closing selector: ${e.message}", e) }
                logger.log(Level.INFO, "Selector closed.")
            }
        }
        serverSocketChannel?.let {
            if (it.isOpen) {
                try { it.close() } catch (e: IOException) { logger.log(Level.WARNING, "Error closing ServerSocketChannel: ${e.message}", e)}
                logger.log(Level.INFO, "ServerSocketChannel closed.")
            }
        }
        clientReadStates.clear()
        clientWriteQueues.clear()
        logger.log(Level.INFO, "Server internal resources cleaned up.")
    }
}
