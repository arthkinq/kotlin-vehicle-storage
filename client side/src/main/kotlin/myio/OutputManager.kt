package myio

interface OutputManager {
    fun write(text: String)
    fun writeLine(text: String)
    fun error(text: String)
}