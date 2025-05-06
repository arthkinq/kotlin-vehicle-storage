package org.example.commands

abstract class Command  (
    private val name: String,
    private val description: String,
    protected val size: Int
) : CommandInterface {
    override fun getName(): String = name
    override fun getDescription(): String = description
    fun checkSizeOfArgs(argsSize: Int) : Boolean {
        return argsSize == size

    }
}