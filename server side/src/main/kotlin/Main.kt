import core.CommandProcessor
import core.ApiServer
import io.ConsoleInputManager
import io.ConsoleOutputManager
import io.IOManager

fun main() {
    val serverIoManager = IOManager(
        ConsoleInputManager(),
        ConsoleOutputManager()
    )

    val collectionFileName = "Collection.csv"
    val networkCommandProcessor = CommandProcessor(serverIoManager, collectionFileName)


    Thread {
        ApiServer(networkCommandProcessor, serverIoManager).startServer()
    }.start()
    serverIoManager.outputLine("Server started. Type 'exitAdmin' in this console to stop the server")
}