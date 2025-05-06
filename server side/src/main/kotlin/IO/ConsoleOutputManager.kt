package org.example.IO

class ConsoleOutputManager : OutputManager {
    override fun write(text: String) = print(text)
    override fun writeLine(text: String) = println(text)
    override fun error(text: String) = System.err.println("ERROR: $text")
}