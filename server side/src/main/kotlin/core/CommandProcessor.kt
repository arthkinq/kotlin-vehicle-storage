package org.example.core

import org.example.IO.IOManager
import org.example.commands.*
import org.example.model.Vehicle

class CommandProcessor(
    private val ioManagerForLogging: IOManager,
    fileName: String
) {

    private val maxRecursionDepth = 5
    private var recursionDepth = 0
    private val executedScripts =
        mutableSetOf<String>()

    private var commandsList: Map<String, CommandInterface>
    val collectionManager = CollectionManager(fileName)

    init {
        commandsList = loadCommandsList()
    }

    private fun loadCommandsList(): Map<String, CommandInterface> {
        val mutableCommands = mutableMapOf<String, CommandInterface>()
        mutableCommands["clear"] = ClearCommand()
        mutableCommands["filter_by_engine_power"] = FilterByEnginePowerCommand()
        mutableCommands["info"] = InfoCommand()
        mutableCommands["min_by_name"] = MinByNameCommand()
        mutableCommands["remove_any_by_engine_power"] = RemoveAnyByEnginePowerCommand()
        mutableCommands["remove_by_id"] = RemoveByIdCommand()
        mutableCommands["remove_first"] = RemoveFirstCommand()
        mutableCommands["show"] = ShowCommand()
        mutableCommands["save"] = SaveCommand()

        mutableCommands["add"] = AddCommand()
        mutableCommands["add_if_max"] = AddIfMaxCommand()
        mutableCommands["add_if_min"] = AddIfMinCommand()
        mutableCommands["update_id"] = UpdateIdCommand()

        mutableCommands["help"] = HelpCommand(mutableCommands.toMap())

        return mutableCommands.toMap()
    }

    fun processCommand(commandBody: List<String>, vehicleFromRequest: Vehicle?): Response {
        if (commandBody.isEmpty()) {
            return Response("Error: Empty command received.")
        }
        val commandName = commandBody[0]
        val commandArgs = commandBody.drop(1)

        val command = commandsList[commandName] ?: run {
            return Response("Unknown command: $commandName")
        }
        return try {
            command.execute(
                args = commandArgs,
                vehicle = vehicleFromRequest,
                collectionManager = collectionManager,
                ioManager = ioManagerForLogging
            )
        } catch (e: Exception) {
            ioManagerForLogging.error("Error executing command '$commandName': ${e.message}")
            Response("Error executing command '$commandName' on server")
        }
    }

    fun getAvailableCommandNames(): List<String> {
        return commandsList.keys.toList()
    }

//
//        fun start() {
//            while (true) {
//                val input = ioManager.readLine().trim()
//                when {
//                    input.isEmpty() -> continue
//                    else -> {
//                        try {
//                            val cmd = Gson().fromJson(input, CommandJSON::class.java)
//                            if (cmd.command == "exit") {
//                                break
//                            }
////                        processCommand(cmd)
//                        } catch (e: Exception) {
//                            println("Invalid JSON format: ${e.message}")
//                        }
//                    }
//                }
//            }
//        }


//    private fun executeScript(input: String) {
//        val parts = input.split("\\s+".toRegex())
//        if (parts.size < 2) {
//            ioManager.error("Syntax: execute_script <filename>")
//            return
//        }
//
//        val filename = parts[1]
//        if (filename in executedScripts) {
//            ioManager.error("Recursion detected: $filename")
//            return
//        }
//
//        if (recursionDepth >= maxRecursionDepth) {
//            throw StackOverflowError("Max script recursion depth ($maxRecursionDepth) exceeded")
//        }
//
//        val path = Paths.get(filename)
//        if (!Files.exists(path)) {
//            ioManager.error("File not found: $filename")
//            return
//        }
//
//        if (!Files.isReadable(path)) {
//            ioManager.error("Access denied: $filename")
//            return
//        }
//
//        recursionDepth++
//        executedScripts.add(filename)
//        try {
//            processScriptFile(path)
//        } catch (e: Exception) {
//            ioManager.error("Script error: ${e.message}")
//        } finally {
//            executedScripts.remove(filename)
//            recursionDepth--
//        }
//    }
//
//    private fun processScriptFile(path: Path) {
//        val originalInput = ioManager.getInput()
//        val scriptInput = object : InputManager {
//            private val reader = Files.newBufferedReader(path)
//            override fun readLine(): String? = reader.readLine()
//            override fun hasInput(): Boolean = reader.ready()
//        }
//        ioManager.setInput(scriptInput)
//
//        try {
//            while (ioManager.hasNextLine()) {
//                val line = ioManager.readLine().trim()
//                if (line.isNotEmpty()) {
//                    ioManager.outputLine("[Script]> $line")
//                    when {
//                        line.startsWith("add", ignoreCase = true) -> processAddCommandInScript()
//                        //else -> processCommand(line)
//                    }
//                }
//            }
//        } finally {
//            ioManager.setInput(originalInput)
//        }
//    }
//
//    private fun processAddCommandInScript() {
//        val vehicleData = mutableListOf<String>()
//        while (ioManager.hasNextLine() && vehicleData.size < 7) {
//            val line = ioManager.readLine().trim()
//            if (line.isNotEmpty()) {
//                vehicleData.add(line)
//            }
//        }
//        if (vehicleData.size == 7) {
//            val fullCommand = "add\n${vehicleData.joinToString("\n")}"
//            //processCommand(fullCommand)
//        } else {
//            ioManager.error("Неполные данные для команды add в скрипте")
//        }
//    }
//
//    fun getCommands(): Map<String, Command> {
//        return commandsList
//    }
//
//    fun setCommands(com: Map<String, Command>) {
//        commandsList = com
//    }
}