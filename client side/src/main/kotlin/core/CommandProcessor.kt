package org.example.core

import ApiClient
import org.example.IO.IOManager
import org.example.IO.InputManager
import org.example.model.Vehicle
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class CommandProcessor(
    private val apiClient: ApiClient,
    private val ioManager: IOManager,
) {
    private val vehicleReader: VehicleReader = VehicleReader(ioManager) // Инициализация здесь
    private val maxRecursionDepth = 5
    private var recursionDepth = 0
    private val executedScripts =
        mutableSetOf<String>() // protection against recursion & may be a file reading in the file
    val listOfCommands = mutableListOf(
        "show",
        "info",
        "help",
        "save",
        "clear",
        "exit",
        "add_if_max",
    )

    fun start() {
        ioManager.outputLine("Transport manager 3000")
        ioManager.outputLine("Fetching available commands from server...")
        try {
            val initialRequest =
                Request(body = listOf("help"), vehicle = null, currentCommandsList = null)
            apiClient.outputStreamHandler(initialRequest)
            listOfCommands.clear()
            listOfCommands.addAll(apiClient.returnNewCommands())
            apiClient.resetNewCommands()
            if (listOfCommands.isNotEmpty()) {
            } else {
                ioManager.outputLine("No commands received from server. Exiting.")
                return
            }
        } catch (e: Exception) {
            ioManager.error("Failed to fetch initial command list from server: ${e.message}. Exiting.")
            return
        }
        while (true) {
            ioManager.outputInline("> ")
            val input = ioManager.readLine().trim()
            val executeScriptRegex = "^execute_script\\s.+\$".toRegex()
            when {
                input == "exit" -> break
                executeScriptRegex.matches(input) -> executeScript(input)
                input.isEmpty() -> continue
                else -> processCommand(input)
            }
        }
    }

    private fun processCommand(input: String) {
        var parts = input.split("\\s+".toRegex(), 2)
        val commandName = parts[0]

        if (listOfCommands.contains(commandName)) {
            var vehicleForRequest: Vehicle? = null
            if (commandName == "add" || commandName == "add_if_max") {
                vehicleForRequest = vehicleReader.readVehicle()
            } else if (commandName == "update") {
                val idToUpdateStr = if (parts.size > 1) parts[1] else {
                    ioManager.outputInline("Enter ID of vehicle to update: ")
                    ioManager.readLine()
                }
                val idToUpdate = idToUpdateStr.toIntOrNull()
                if (idToUpdate == null) {
                    ioManager.error("Invalid ID format for update.")
                    // return или continue, в зависимости от желаемого поведения
                } else {
                    ioManager.outputLine("Enter new data for vehicle with ID $idToUpdate:")
                    val newVehicleData = vehicleReader.readVehicle() // readVehicle() создаст временный ID
                    // Создаем объект Vehicle с правильным ID для обновления и новыми данными
                    vehicleForRequest = Vehicle(
                        id = idToUpdate, // Используем ID, который нужно обновить
                        name = newVehicleData.name,
                        coordinates = newVehicleData.coordinates,
                        creationDate = 0L, // Сервер должен игнорировать это поле при обновлении и сохранить старую дату
                        enginePower = newVehicleData.enginePower,
                        distanceTravelled = newVehicleData.distanceTravelled,
                        type = newVehicleData.type,
                        fuelType = newVehicleData.fuelType
                    )
                    parts = listOf(commandName, idToUpdate.toString())
                }
            }
            val request = Request(body = parts, vehicle = vehicleForRequest, currentCommandsList = listOfCommands)
            apiClient.outputStreamHandler(request)
            listOfCommands.addAll(apiClient.returnNewCommands())
            apiClient.resetNewCommands()
        } else {
            ioManager.outputLine("Command ${commandName} not found")
        }
        //TODO execute script
    }

    private fun executeScript(nameOfFile: String) {
        if (nameOfFile in executedScripts) {
            ioManager.error("Recursion detected: $nameOfFile")
            return
        }

        if (recursionDepth >= maxRecursionDepth) {
            throw StackOverflowError("Max script recursion depth ($maxRecursionDepth) exceeded")
        }

        val path = Paths.get(nameOfFile)
        if (!Files.exists(path)) {
            ioManager.error("File not found: $nameOfFile")
            return
        }

        if (!Files.isReadable(path)) {
            ioManager.error("Access denied: $nameOfFile")
            return
        }

        recursionDepth++
        executedScripts.add(nameOfFile)
        try {
            processScriptFile(path)
        } catch (e: Exception) {
            ioManager.error("Script error: ${e.message}")
        } finally {
            executedScripts.remove(nameOfFile)
            recursionDepth--
        }
    }

    private fun processScriptFile(path: Path) {
        val originalInput = ioManager.getInput()
        val scriptInput = object : InputManager {
            private val reader = Files.newBufferedReader(path)
            override fun readLine(): String? = reader.readLine()
            override fun hasInput(): Boolean = reader.ready()
        }
        ioManager.setInput(scriptInput)

        try {
            while (ioManager.hasNextLine()) {
                val line = ioManager.readLine().trim() ?: continue
                if (line.isNotEmpty()) {
                    ioManager.outputLine("[Script]> $line")
                    when {
                        line.startsWith("add", ignoreCase = true) -> processAddCommandInScript()
                        else -> processCommand(line)
                    }
                }
            }
        } finally {
            ioManager.setInput(originalInput)
        }
    }

    private fun processAddCommandInScript() {
        val vehicleData = mutableListOf<String>()
        while (ioManager.hasNextLine() && vehicleData.size < 7) {
            val line = ioManager.readLine().trim()
            if (line.isNotEmpty()) {
                vehicleData.add(line)
            }
        }
        if (vehicleData.size == 7) {
            val fullCommand = "add\n${vehicleData.joinToString("\n")}"
            processCommand(fullCommand)
        } else {
            ioManager.error("Неполные данные для команды add в скрипте")
        }
    }
}
