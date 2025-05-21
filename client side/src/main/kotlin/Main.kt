import core.ApiClient
import core.CommandProcessor
import myio.IOManager
import myio.ConsoleInputManager
import myio.ConsoleOutputManager

fun main() {
    val ioManager = IOManager(
        ConsoleInputManager(),
        ConsoleOutputManager()
    )

    val serverHostIp = "localhost"
    val serverPort = 8888
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
