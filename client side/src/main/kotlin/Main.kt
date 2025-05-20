import myio.IOManager
import myio.ConsoleInputManager
import myio.ConsoleOutputManager
import core.*

fun main(args: Array<String>) {
    val ioManager = IOManager(
        ConsoleInputManager(),
        ConsoleOutputManager()
    )

    val serverHostIp: String
    val serverPort: Int
    if (args.isNotEmpty() && args.size > 1) {
        serverHostIp = args[0]
        serverPort = args[1].toInt()
        ioManager.outputLine("Using server IP from command line argument: $serverHostIp")
    } else {
        ioManager.outputInline("Enter server IP address (e.g., 192.168.1.105) or press Enter for localhost: ")
        val userInputHost = ioManager.readLine().trim()
        serverHostIp = userInputHost.ifBlank { "localhost" }
        serverPort = 8888
    }
    ioManager.outputLine("Connecting to server at: $serverHostIp:$serverPort")


    val apiClient = ApiClient(ioManager, serverHost = serverHostIp, serverPort = serverPort)
    val commandProcessor =
        CommandProcessor(apiClient, ioManager)

    try {
        commandProcessor.start()
    } catch (e: Exception) {
        ioManager.error("An unexpected error occurred in the client: ${e.message}")
        e.printStackTrace()
    } finally {
        apiClient.close()
        ioManager.outputLine("Client application finished.")
    }
}
