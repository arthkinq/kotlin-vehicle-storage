package org.example.core

import org.example.IO.IOManager
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.nio.channels.AsynchronousCloseException // Явный импорт для ясности
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.system.exitProcess

class ApiServer(
    private val commandProcessor: CommandProcessor,
    private val ioManager: IOManager
) {
    private val logger = Logger.getLogger(ApiServer::class.java.name)

    companion object {
        private const val DEFAULT_PORT = 8888
        private const val DEFAULT_HOST = "localhost"
    }

    // Ссылка на серверный сокет, чтобы его можно было закрыть из другого потока
    @Volatile // Обеспечивает видимость изменений этого поля между потоками
    private var mainServerSocketChannel: ServerSocketChannel? = null

    fun startServer(port: Int = DEFAULT_PORT) {
        logger.log(Level.INFO, "Server is preparing to start on $DEFAULT_HOST:$port. Waiting for connections...")

        try {
            val serverSocketChannel = ServerSocketChannel.open()
            serverSocketChannel.bind(InetSocketAddress(DEFAULT_HOST, port))
            this.mainServerSocketChannel = serverSocketChannel

            // ПОТОК АДМИНСКИХ КОМАНД
            Thread {
                adminConsoleLoop()
            }.apply {
                name = "AdminConsoleThread"
                isDaemon = true // Поток завершится вместе с основным приложением
                start()
            }
            logger.log(Level.INFO, "Admin console input thread started. Type admin commands here.")

            while (serverSocketChannel.isOpen) {
                try {
                    val clientSocketChannel = serverSocketChannel.accept()
                    if (clientSocketChannel != null) {
                        val clientAddress = try {
                            clientSocketChannel.remoteAddress?.toString()
                        } catch (e: Exception) {
                            "N/A"
                        }
                        logger.log(Level.INFO, "Client connected: $clientAddress")
                        // Для простоты пока обрабатываем клиентов последовательно.
                        // Для параллельной обработки: Thread { handleClientRequest(clientSocketChannel, clientAddress) }.start()
                        if (clientAddress != null) {
                            handleClientRequest(clientSocketChannel, clientAddress)
                        }
                    }
                } catch (e: AsynchronousCloseException) {
                    logger.log(
                        Level.INFO,
                        "Server socket closed (likely by 'exitAdmin' command or external interrupt), stopping accept loop"
                    )
                    break
                } catch (e: SocketException) {
                    //если сокет закрыт во время accept
                    if (!serverSocketChannel.isOpen) {
                        logger.log(Level.INFO, "Server socket was closed while accepting, server is shutting down.")
                        break
                    } else {
                        logger.log(Level.WARNING, "SocketException during accept: ${e.message}", e)
                    }
                } catch (e: Exception) {
                    if (serverSocketChannel.isOpen) {
                        logger.log(Level.WARNING, "Error accepting client connection: ${e.message}", e)
                    } else {
                        // Если сокет уже закрыт, это может быть частью процесса завершения
                        logger.log(Level.INFO, "Exception during accept, but server socket is already closed.")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Critical server error during startup or main loop: ${e.message}", e)
        } finally {
            mainServerSocketChannel?.takeIf { it.isOpen }?.close()
            logger.log(Level.INFO, "Server stopped.")
        }
    }


    private fun adminConsoleLoop() {
        logger.log(Level.INFO, "AdminConsoleThread: Started. Waiting for admin commands.")
        try {
            while (true) {
                val serverAdminCommand = ioManager.readLine()
                logger.log(Level.INFO, "AdminConsoleThread: Received command: '$serverAdminCommand'")

                when (serverAdminCommand.trim().lowercase()) {
                    "exitadmin" -> {
                        logger.log(Level.INFO, "AdminConsoleThread: Processing 'exitAdmin'.")
                        try {
                            mainServerSocketChannel?.takeIf { it.isOpen }?.close()
                            logger.log(Level.INFO, "AdminConsoleThread: Main server socket closed.")
                        } catch (e: Exception) {
                            logger.log(
                                Level.WARNING,
                                "AdminConsoleThread: Exception while closing main server socket: ${e.message}",
                                e
                            )
                        }
                        logger.log(Level.INFO, "AdminConsoleThread: Calling exitProcess(0).")
                        exitProcess(0)
                    }
//                    "saveadmin" -> {
//                        logger.log(Level.INFO, "AdminConsoleThread: Processing 'saveAdmin'.")
//                        val saveResponse = commandProcessor.processCommand(listOf("save"), null)
//                    }
                    "serveraddress" -> {
                        ioManager.outputLine("Admin command 'serveraddress' received. Fetching server addresses...")
                        logger.log(Level.INFO, "AdminConsoleThread: Processing 'serveraddress'.")
                        displayServerAddresses()
                    }

                    else -> {
                        if (serverAdminCommand.isNotBlank()) {
                            logger.log(Level.INFO, "AdminConsoleThread: Unknown command: '$serverAdminCommand'")
                        }
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt() // Восстанавливаем статус прерывания
            logger.log(Level.INFO, "AdminConsoleThread: Interrupted.")
        } catch (e: java.io.IOException) {

            if (e.message?.contains("Stream closed", ignoreCase = true) == true ||
                e.message?.contains("Bad file descriptor", ignoreCase = true) == true
            ) {
                logger.log(Level.INFO, "AdminConsoleThread: System.in stream closed, thread finishing.")
            } else {
                logger.log(Level.SEVERE, "AdminConsoleThread: IOException: ${e.message}", e)
            }
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "AdminConsoleThread: Unexpected error: ${e.message}", e)
        } finally {
            logger.log(Level.INFO, "AdminConsoleThread: Finished.")
        }
    }


    private fun handleClientRequest(
        clientSocketChannel: SocketChannel,
        clientAddressForLogging: String
    ) { // Передаем clientAddressForLogging, чтобы избежать повторного вызова getRemoteAddress на потенциально закрытом канале
        logger.log(Level.INFO, "Handling request from $clientAddressForLogging")
        try {
            ObjectInputStream(clientSocketChannel.socket().getInputStream()).use { objectInputStream ->
                val request = objectInputStream.readObject() as Request
                logger.log(
                    Level.INFO,
                    "Received request from $clientAddressForLogging: command='${request.body.getOrNull(0)}'"
                )

                val response = commandProcessor.processCommand(request.body, request.vehicle)

                response.updateCommands(commandProcessor.getAvailableCommandNames())
                logger.log(Level.INFO, "Sent available commands list to $clientAddressForLogging.")


                ObjectOutputStream(clientSocketChannel.socket().getOutputStream()).use { objectOutputStream ->
                    objectOutputStream.writeObject(response)
                    logger.log(Level.INFO, "Sent response to $clientAddressForLogging")
                }
            }
        } catch (e: java.io.EOFException) {
            logger.log(Level.WARNING, "Client $clientAddressForLogging disconnected abruptly or sent no data.")
        } catch (e: SocketException) {
            logger.log(
                Level.WARNING,
                "Socket issue with client $clientAddressForLogging: ${e.message} (client might have closed connection)."
            )
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Error handling client request from $clientAddressForLogging: ${e.message}", e)
            try {
                if (clientSocketChannel.isOpen) {
                    ObjectOutputStream(clientSocketChannel.socket().getOutputStream()).use { oos ->
                        oos.writeObject(Response("Server error while processing your request. Please try again."))
                    }
                }
            } catch (sendError: Exception) {
                logger.log(Level.SEVERE, "Failed to send error response to client $clientAddressForLogging", sendError)
            }
        } finally {
            try {
                if (clientSocketChannel.isOpen) {
                    clientSocketChannel.close()
                    logger.log(Level.INFO, "Client connection closed: $clientAddressForLogging")
                } else {
                    logger.log(Level.INFO, "Client connection was already closed: $clientAddressForLogging.")
                }
            } catch (e: Exception) {
                logger.log(Level.WARNING, "Error closing client socket for $clientAddressForLogging: ${e.message}", e)
            }
        }
    }

    private fun displayServerAddresses() {
        val addresses = StringBuilder("Server Network Addresses:\n")
        val port = mainServerSocketChannel?.localAddress?.let { (it as? InetSocketAddress)?.port } ?: DEFAULT_PORT
        try {
            val localHost = InetAddress.getLocalHost()
            addresses.append("  Hostname: ${localHost.hostName}\n")
            // Адрес, к которому сервер привязан (bind address)
            val boundAddress =
                mainServerSocketChannel?.localAddress?.let { (it as? InetSocketAddress)?.address?.hostAddress }
                    ?: DEFAULT_HOST
            addresses.append("  Server is bound to: $boundAddress (listening on all interfaces if 0.0.0.0)\n")
            addresses.append("  Port: $port\n\n")


            addresses.append("  Potential connection addresses (for clients):\n")
            var foundNonLoopback = false
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()
            while (networkInterfaces.hasMoreElements()) {
                val ni = networkInterfaces.nextElement()
                if (ni.isUp && !ni.isLoopback) { // Только активные и не loopback интерфейсы
                    val inetAddresses = ni.inetAddresses
                    while (inetAddresses.hasMoreElements()) {
                        val inetAddr = inetAddresses.nextElement()
                        if (inetAddr is java.net.Inet4Address) { // Отображаем IPv4 для простоты
                            addresses.append("    Interface: ${ni.displayName} -> IP: ${inetAddr.hostAddress}:$port\n")
                            foundNonLoopback = true
                        }
                        // else if (inetAddr is java.net.Inet6Address) {
                        //     addresses.append("    Interface: ${ni.displayName} -> IPv6: ${inetAddr.hostAddress}%${ni.name}:$port\n")
                        // }
                    }
                }
            }
            if (!foundNonLoopback) {
                addresses.append("    No non-loopback IPv4 addresses found. If server is bound to 0.0.0.0, it should be accessible via any of its configured IPs.\n")
                addresses.append("    Try connecting to 'localhost:$port' or '127.0.0.1:$port' from the same machine.\n")
            }
        } catch (e: SocketException) {
            addresses.append("  Error getting network interface information: ${e.message}\n")
            addresses.append("  Server is bound to $DEFAULT_HOST:$port (as configured at startup).\n")
            logger.log(Level.WARNING, "SocketException while getting server addresses: ${e.message}", e)
        } catch (e: Exception) {
            addresses.append("  An unexpected error occurred while fetching server addresses: ${e.message}\n")
            logger.log(Level.WARNING, "Unexpected error getting server addresses: ${e.message}", e)
        }
        logger.log(Level.INFO, addresses.toString())
    }
}