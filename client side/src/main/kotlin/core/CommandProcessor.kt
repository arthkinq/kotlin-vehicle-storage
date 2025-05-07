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
    private val vehicleReader: VehicleReader = VehicleReader(ioManager)
    private val maxRecursionDepth = 5
    private var recursionDepth = 0
    private val executedScripts =
        mutableSetOf<String>()
    private val listOfCommands = mutableListOf("help")

    fun start() {
        ioManager.outputLine("Transport manager 3000")
        ioManager.outputLine("Fetching available commands from server...")
        try {
            val initialRequest =
                Request(body = listOf("help"), vehicle = null, currentCommandsList = null)
            apiClient.outputStreamHandler(initialRequest)
            listOfCommands.clear()
            listOfCommands.addAll(apiClient.returnNewCommands()) // Gets commands populated by inputStreamHandler
            apiClient.resetNewCommands()

            if (listOfCommands.isEmpty()) { // This condition is now being met
                ioManager.outputLine("No commands received from server. Exiting.")
                return
            }
        } catch (e: Exception) { // This 'catch' block is being hit, causing "Output error!"
            ioManager.error("Failed to fetch initial command list from server: ${e.message}. Exiting.")
            return
        }
        while (true) {
            ioManager.outputInline("> ")
            val input = ioManager.readLine().trim()
            val parts = input.split("\\s+".toRegex(), 2) // Разделяем на команду и остальное (не более 2 частей)
            val commandName = parts.getOrNull(0) ?: ""
            val argument = parts.getOrNull(1) ?: ""

            when (commandName) {
                "exit" -> break
                "execute_script" -> {
                    if (argument.isNotBlank()) {
                        executeScript(argument)
                    } else {
                        ioManager.outputLine("Usage: execute_script <filename>")
                    }
                }

                "" -> continue
                else -> processCommand(input)
            }

        }
    }

    private fun processCommand(input: String) {
        val parts = input.split("\\s+".toRegex()).toMutableList()
        val commandName = parts.removeAt(0)
        if (!listOfCommands.contains(commandName) && commandName != "execute_script") {
            ioManager.outputLine("Command '$commandName' is not recognized by the client or wasn't loaded from server. Type 'help'.")
            return
        }
        if (commandName == "execute_script") {
            if (parts.isNotEmpty()) {
                executeScript(parts.joinToString(" ")) // Если имя файла может содержать пробелы
            } else {
                ioManager.outputLine("Usage: execute_script <file_path>")
            }
            return
        }

        var vehicleForRequest: Vehicle? = null
        var finalCommandBody: List<String> = listOf(commandName) + parts
        when (commandName) {
            "add", "add_if_max", "add_if_min" -> {
                ioManager.outputLine("Please enter data for the new vehicle:")
                vehicleForRequest = vehicleReader.readVehicle()
                finalCommandBody =
                    listOf(commandName) // Для этих команд аргументы в body не нужны, Vehicle идет отдельно
            }

            "update_id" -> {
                if (parts.isEmpty()) {
                    ioManager.outputLine("Usage: update_id <ID_to_update>")
                    ioManager.outputInline("Enter ID of vehicle to update: ")
                    val idStr = ioManager.readLine()
                    if (idStr.isNotBlank()) parts.add(idStr)
                    else {
                        ioManager.error("ID is required for update_id.")
                        return
                    }
                }
                val idToUpdateStr = parts[0]
                val idToUpdate = idToUpdateStr.toIntOrNull()

                if (idToUpdate == null) {
                    ioManager.error("Invalid ID format '$idToUpdateStr' for update_id.")
                    return
                } else {
                    ioManager.outputLine("Enter new data for vehicle with ID $idToUpdate:")
                    val newVehicleDataFromReader = vehicleReader.readVehicle()
                    vehicleForRequest = Vehicle(
                        id = idToUpdate, // Клиент устанавливает ID, который нужно обновить
                        name = newVehicleDataFromReader.name,
                        coordinates = newVehicleDataFromReader.coordinates,
                        creationDate = 0L, // Сервер должен игнорировать и сохранить старую
                        enginePower = newVehicleDataFromReader.enginePower,
                        distanceTravelled = newVehicleDataFromReader.distanceTravelled,
                        type = newVehicleDataFromReader.type,
                        fuelType = newVehicleDataFromReader.fuelType
                    )
                    // Сервер ожидает ID в аргументах и Vehicle с этим же ID в объекте
                    finalCommandBody = listOf(commandName, idToUpdate.toString())
                }
            }

            "remove_by_id" -> {
                if (parts.isEmpty()) {
                    ioManager.outputLine("Usage: remove_by_id <ID>")
                    return
                }
                // finalCommandBody уже содержит [commandName, id]
            }

            "filter_by_engine_power" -> {
                if (parts.isEmpty()) {
                    ioManager.outputLine("Usage: filter_by_engine_power <powerValue>")
                    return
                }
                // finalCommandBody уже содержит [commandName, powerValue]
            }
            // Для команд "show", "info", "help", "clear", "remove_first"
            // finalCommandBody будет listOf(commandName), vehicleForRequest останется null.
            // Для "save", если есть аргумент, он будет в mutableParts и попадет в finalCommandBody.
        }

        val request =
            Request(body = finalCommandBody, vehicle = vehicleForRequest, currentCommandsList = listOfCommands)
        apiClient.outputStreamHandler(request)

        // Обновление списка команд после КАЖДОГО ответа, так как сервер может добавлять/удалять команды динамически
        val serverNewCommands = apiClient.returnNewCommands()
        if (serverNewCommands.isNotEmpty()) {
            listOfCommands.clear() // Полностью заменяем список команд на тот, что пришел от сервера
            listOfCommands.addAll(serverNewCommands)
            ioManager.outputLine("Client command list updated from server.")
        }
        apiClient.resetNewCommands()
    }


//        if (listOfCommands.contains(commandName) ) {
//            if (commandName == "add" || commandName == "add_if_max") {
//                vehicleForRequest = vehicleReader.readVehicle()
//            } else if (commandName == "update") {
//                val idToUpdateStr = if (parts.size > 1) parts[1] else {
//                    ioManager.outputInline("Enter ID of vehicle to update: ")
//                    ioManager.readLine()
//                }
//                val idToUpdate = idToUpdateStr.toIntOrNull()
//                if (idToUpdate == null) {
//                    ioManager.error("Invalid ID format for update.")
//                    // return или continue, в зависимости от желаемого поведения
//                } else {
//                    ioManager.outputLine("Enter new data for vehicle with ID $idToUpdate:")
//                    val newVehicleData = vehicleReader.readVehicle() // readVehicle() создаст временный ID
//                    // Создаем объект Vehicle с правильным ID для обновления и новыми данными
//                    vehicleForRequest = Vehicle(
//                        id = idToUpdate, // Используем ID, который нужно обновить
//                        name = newVehicleData.name,
//                        coordinates = newVehicleData.coordinates,
//                        creationDate = 0L, // Сервер должен игнорировать это поле при обновлении и сохранить старую дату
//                        enginePower = newVehicleData.enginePower,
//                        distanceTravelled = newVehicleData.distanceTravelled,
//                        type = newVehicleData.type,
//                        fuelType = newVehicleData.fuelType
//                    )
//                    parts = listOf(commandName, idToUpdate.toString())
//                }
//            }
//            val request = Request(body = parts, vehicle = vehicleForRequest, currentCommandsList = listOfCommands)
//            apiClient.outputStreamHandler(request)
//            listOfCommands.addAll(apiClient.returnNewCommands())
//            apiClient.resetNewCommands()
//        } else {
//            ioManager.outputLine("Command ${commandName} not found")
//        }


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
        ioManager.outputLine("Expecting 7 lines of vehicle data for 'add' from script...")
        var linesRead = 0
        while (ioManager.hasNextLine() && linesRead < 7) {
            val line = ioManager.readLine().trim()
            if (line.isNotEmpty()) {
                vehicleData.add(line)
                linesRead++
            } else {
                // Handle empty line in script if necessary, maybe skip or error
                ioManager.outputLine("Warning: Empty line encountered in script for vehicle data.")
            }
        }

        if (vehicleData.size == 7) {
            try {
                // Consider adding more validation to data read from script before parsing
                val vehicleFromScript = vehicleReader.readVehicleFromScript(vehicleData)
                val request = Request(
                    body = listOf("add"),
                    vehicle = vehicleFromScript,
                    currentCommandsList = listOfCommands
                )
                apiClient.outputStreamHandler(request)

                val serverNewCommands = apiClient.returnNewCommands()
                if (serverNewCommands.isNotEmpty()) {
                    listOfCommands.clear()
                    listOfCommands.addAll(serverNewCommands)
                }
                apiClient.resetNewCommands()
            } catch (e: Exception) {
                ioManager.error("Error processing 'add' command from script: ${e.message}. Data was: $vehicleData")
            }
        } else {
            ioManager.error("Not enough data for 'add' command in script. Expected 7 lines, got ${vehicleData.size}. Data: $vehicleData")
        }
    }
}