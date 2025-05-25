import core.CommandProcessor
import core.ApiServer
import db.DatabaseManager
import db.UserDAO
import myio.ConsoleInputManager
import myio.ConsoleOutputManager
import myio.IOManager

//TODO: крч я тут хуйни наверняка добавила надо делитнуть в онце
fun main() {
    val c = DatabaseManager.getConnection()
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