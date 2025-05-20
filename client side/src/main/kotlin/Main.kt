import myio.IOManager
import myio.ConsoleInputManager
import myio.ConsoleOutputManager
import core.*

fun main() {
    val ioManager = IOManager(
        ConsoleInputManager(),
        ConsoleOutputManager()
    )
    val apiClient = ApiClient(ioManager)
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
