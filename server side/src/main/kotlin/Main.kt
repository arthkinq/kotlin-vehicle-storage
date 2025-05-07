package org.example

import org.example.IO.IOManager
import org.example.core.CommandProcessor
import org.example.IO.ConsoleInputManager
import org.example.IO.ConsoleOutputManager
import org.example.core.ApiServer

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