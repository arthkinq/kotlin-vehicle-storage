import core.CommandProcessor
import core.ApiServer
import db.DatabaseManager
import db.UserDAO
import myio.ConsoleInputManager
import myio.ConsoleOutputManager
import myio.IOManager

fun main() {
    val serverIoManager = IOManager(
        ConsoleInputManager(),
        ConsoleOutputManager()
    )

    val networkCommandProcessor = CommandProcessor(serverIoManager)
    Thread {
        ApiServer(networkCommandProcessor, serverIoManager).startServer()
    }.start()
    serverIoManager.outputLine("Server started. Type 'exitAdmin' in this console to stop the server ")
}