package org.example

import org.example.IO.IOManager
import org.example.IO.ConsoleInputManager
import org.example.IO.ConsoleOutputManager
import org.example.core.*

fun main() {
    val ioManager = IOManager(
        ConsoleInputManager(),
        ConsoleOutputManager()
    )
    val apiClient = ApiClient(ioManager) // Возможно, передача хоста/порта, если не дефолтные
    val commandProcessor =
        CommandProcessor(apiClient, ioManager) // Уточните пакет для клиентского CommandProcessor

    try {
        commandProcessor.start()
    } catch (e: Exception) {
        ioManager.error("An unexpected error occurred in the client: ${e.message}")
        e.printStackTrace()
    } finally {
        apiClient.close() // <--- ВАЖНО
        ioManager.outputLine("Client application finished.")
    }
}
