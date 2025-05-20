package myio

import java.util.*

class ConsoleInputManager : InputManager {
    private val scanner = Scanner(System.`in`)

    override fun readLine(): String? = scanner.nextLine().takeIf { it.isNotBlank() }
    override fun hasInput(): Boolean = System.`in`.available() > 0
}