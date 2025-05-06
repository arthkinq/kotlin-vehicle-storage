package org.example

import ApiClient
import org.example.IO.IOManager
import org.example.IO.ConsoleInputManager
import org.example.IO.ConsoleOutputManager
import org.example.core.*

fun main() {
    val ioManager = IOManager(
        ConsoleInputManager(),
        ConsoleOutputManager()
    )
    val apiClient = ApiClient(ioManager)
    CommandProcessor(apiClient, ioManager).start()
}
