package org.example.core

import org.example.IO.IOManager
import org.example.IO.InputManager
import org.example.model.Vehicle
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

class CommandProcessor(
    private val apiClient: ApiClient,
    private val ioManager: IOManager
) {
    private val vehicleReader: VehicleReader = VehicleReader(ioManager)

    private val maxRecursionDepth = 5
    private var recursionDepth = 0
    private val executedScripts = mutableSetOf<String>()

    // Единый список для хранения имен команд, как они приходят от сервера (с плюсом, если он есть)
    private var rawServerCommands = mutableListOf<String>()

    // Множество для быстрого определения "чистых" имен команд, требующих VehicleReader
    private val commandsRequiringVehicleInput = mutableSetOf<String>()
    // Множество для быстрого определения всех "чистых" имен команд
    private val knownCleanCommandNames = mutableSetOf<String>()


    init {
        apiClient.onCommandsUpdated = { newRawCommands ->
            updateCommandLists(newRawCommands)
            ioManager.outputLine("Client command list updated from server (${knownCleanCommandNames.size} known commands).")
        }
    }

    private fun updateCommandLists(newRawCommands: List<String>) {
        this.rawServerCommands.clear()
        this.rawServerCommands.addAll(newRawCommands)

        this.commandsRequiringVehicleInput.clear()
        this.knownCleanCommandNames.clear()

        newRawCommands.forEach { rawCommandName ->
            if (rawCommandName.endsWith("+")) {
                val cleanName = rawCommandName.removeSuffix("+").lowercase()
                this.commandsRequiringVehicleInput.add(cleanName)
                this.knownCleanCommandNames.add(cleanName)
            } else {
                this.knownCleanCommandNames.add(rawCommandName.lowercase())
            }
        }
        // Отладочный вывод
        // logger.info("Updated knownCleanCommandNames: $knownCleanCommandNames")
        // logger.info("Updated commandsRequiringVehicleInput: $commandsRequiringVehicleInput")
    }


    fun start() {
        ioManager.outputLine("Transport Manager Client")

        // Ожидаем первоначального получения списка команд через callback
        var initialWaitCycles = 20 // Увеличим ожидание до ~10 секунд
        while (knownCleanCommandNames.isEmpty() && initialWaitCycles > 0 && apiClient.running.get()) {
            try {
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                ioManager.error("Interrupted during initial command list wait.")
                apiClient.close() // Закрываем клиент, если прервали ожидание
                return
            }
            initialWaitCycles--
            if (knownCleanCommandNames.isNotEmpty()) break
            if (initialWaitCycles % 4 == 0) {
                ioManager.outputLine("Waiting for initial command list from server...")
            }
        }

        if (knownCleanCommandNames.isEmpty()) {
            if (apiClient.running.get()) {
                ioManager.error("Failed to fetch initial command list from server. Server might be unavailable or no commands configured.")
                ioManager.outputLine("You can try commands, but they might not be recognized until connection is (re)established and commands are received.")
                // Не выходим, позволяем клиенту работать и пытаться переподключиться
            } else {
                ioManager.outputLine("ApiClient stopped during initialization.")
                return
            }
        } else {
            ioManager.outputLine("Initial command list received. Type 'help' for available commands.")
        }

        var continueExecution = true
        while (continueExecution && apiClient.running.get()) {
            ioManager.outputInline("> ")
            val userInput = ioManager.readLine().trim()
            if (!apiClient.running.get() && userInput.isBlank()) break // Выход если клиент остановлен во время чтения

            val parts = userInput.split("\\s+".toRegex(), 2)
            val commandNameInput = parts.getOrNull(0)?.lowercase() ?: "" // Сразу в lowercase
            val argument = parts.getOrNull(1) ?: ""

            when (commandNameInput) {
                "exit" -> {
                    ioManager.outputLine("Exiting client application...")
                    continueExecution = false
                }
                "execute_script" -> {
                    if (argument.isNotBlank()) {
                        executeScript(argument)
                    } else {
                        ioManager.outputLine("Usage: execute_script <filename>")
                    }
                }
                "" -> { /* Пустой ввод, ничего не делаем */ }
                else -> {
                    processSingleCommand(commandNameInput, if (parts.size > 1) parts.drop(1) else emptyList())
                }
            }
        }
        ioManager.outputLine("Client command loop finished.")
        apiClient.close() // Убедимся, что клиент закрывается при выходе из цикла
    }

    private fun processSingleCommand(commandNameInLowercase: String, args: List<String>) {
        // Проверяем, известна ли "чистая" команда клиенту
        if (!knownCleanCommandNames.contains(commandNameInLowercase)) {
            ioManager.outputLine("Command '$commandNameInLowercase' is not recognized by the client. Type 'help' for available commands (if list was received).")
            return
        }

        var vehicleForRequest: Vehicle? = null
        // Имя команды для отправки на сервер: если она была с "+", отправляем с "+".
        // Иначе отправляем "чистое" имя.
        val commandNameToSend = rawServerCommands.firstOrNull {
            it.removeSuffix("+").equals(commandNameInLowercase, ignoreCase = true) ||
                    it.equals(commandNameInLowercase, ignoreCase = true)
        } ?: commandNameInLowercase // Если не нашли в raw (маловероятно, но для безопасности)

        var finalCommandBody: List<String> = listOf(commandNameToSend) + args

        // Специальная логика для команд, требующих Vehicle
        if (commandsRequiringVehicleInput.contains(commandNameInLowercase)) {
            // Команды типа "add", "add_if_max", "add_if_min"
            if (commandNameInLowercase == "add" || commandNameInLowercase == "add_if_max" || commandNameInLowercase == "add_if_min") {
                ioManager.outputLine("Please enter data for the new vehicle:")
                vehicleForRequest = vehicleReader.readVehicle()
                // Аргументы для этих команд не нужны, сервер берет Vehicle из запроса
                finalCommandBody = listOf(commandNameToSend) // Только имя команды (с "+")
            }
            // Команда "update_id"
            else if (commandNameInLowercase == "update_id") {
                val idArg = args.getOrNull(0) ?: run {
                    ioManager.outputLine("Usage: update_id <ID_to_update>")
                    ioManager.outputInline("Enter ID of vehicle to update: ")
                    ioManager.readLine().takeIf { !it.isNullOrBlank() }
                }

                if (idArg.isNullOrBlank()) {
                    ioManager.error("ID is required for 'update_id' command.")
                    return
                }
                val idToUpdate = idArg.toIntOrNull()
                if (idToUpdate == null) {
                    ioManager.error("Invalid ID format '$idArg' for 'update_id'. ID must be an integer.")
                    return
                }

                ioManager.outputLine("Enter new data for vehicle with ID $idToUpdate:")
                val newVehicleDataFromReader = vehicleReader.readVehicle()
                vehicleForRequest = newVehicleDataFromReader.copy(id = idToUpdate) // Копируем данные, но ставим нужный ID
                // Сервер ожидает ID в аргументах команды, а само тело Vehicle в объекте.
                // Имя команды уже содержит "+", если нужно.
                finalCommandBody = listOf(commandNameToSend, idToUpdate.toString())
            }
            // Добавь сюда другие команды, если они требуют Vehicle и имеют особую логику аргументов
            else {
                ioManager.outputLine("Please enter data for the vehicle for command '$commandNameInLowercase':")
                vehicleForRequest = vehicleReader.readVehicle()
                // Если для других команд с "+" аргументы не нужны, а только Vehicle:
                finalCommandBody = listOf(commandNameToSend)
            }
        }

        val request = Request(
            body = finalCommandBody,
            vehicle = vehicleForRequest,
            // Передаем "сырой" список команд, если серверу это нужно для какой-то синхронизации
            // Либо можно передавать null, если сервер всегда сам знает актуальный список
            currentCommandsList = ArrayList(rawServerCommands)
        )

        ioManager.outputLine("Sending command '$commandNameToSend' to server...")
        val response = apiClient.sendRequestAndWaitForResponse(request)

        if (response != null) {
            ioManager.outputLine(response.responseText)
            // Обновление списка команд теперь происходит через callback apiClient.onCommandsUpdated,
            // который вызывается в ApiClient.handleRead, если response.newCommandsList не пуст.
            // Поэтому здесь явное обновление не нужно, чтобы избежать двойного обновления.
        } else {
            ioManager.error("No response received from server or request timed out for command: $commandNameToSend")
        }
    }

    private fun executeScript(fileName: String) {
        if (recursionDepth >= maxRecursionDepth) {
            ioManager.error("Max script recursion depth ($maxRecursionDepth) exceeded. Aborting script '$fileName'.")
            return
        }

        val filePathString = Paths.get(fileName).toAbsolutePath().toString()
        if (filePathString in executedScripts) {
            ioManager.error("Recursion detected: Script '$fileName' (resolved to '$filePathString') is already running. Aborting.")
            return
        }

        val path: Path
        try {
            path = Paths.get(fileName)
            if (!Files.exists(path)) {
                ioManager.error("Script file not found: $fileName (Resolved to: ${path.toAbsolutePath()})")
                return
            }
            if (!Files.isReadable(path)) {
                ioManager.error("Cannot read script file (access denied): $fileName")
                return
            }
        } catch (e: InvalidPathException) {
            ioManager.error("Invalid script file path '$fileName': ${e.message}")
            return
        } catch (e: Exception) {
            ioManager.error("Error accessing script file '$fileName': ${e.message}")
            return
        }

        recursionDepth++
        executedScripts.add(filePathString)
        ioManager.outputLine("Executing script: $fileName (Depth: $recursionDepth)")

        val originalInputManager = ioManager.getInput()
        try {
            Files.newBufferedReader(path).use { reader ->
                val scriptInputManager = object : InputManager {
                    override fun readLine(): String? = reader.readLine()
                    override fun hasInput(): Boolean = reader.ready() // Для файлов ready() обычно работает нормально
                }
                ioManager.setInput(scriptInputManager)

                var lineNumber = 0
                while (true) { // Используем readLine() == null для проверки конца файла
                    lineNumber++
                    val line = scriptInputManager.readLine()?.trim() ?: break
                    if (line.isEmpty() || line.startsWith("#")) continue

                    ioManager.outputLine("[Script Line $lineNumber]> $line")
                    val parts = line.split("\\s+".toRegex(), 2)
                    val commandNameFromScript = parts.getOrNull(0)?.lowercase() ?: ""
                    val argument = parts.getOrNull(1) ?: ""

                    when (commandNameFromScript) {
                        "execute_script" -> {
                            if (argument.isNotBlank()) executeScript(argument)
                            else ioManager.error("[Script Error Line $lineNumber] Usage: execute_script <filename>")
                        }
                        // Для команд add* в скрипте, используем специальный обработчик
                        "add", "add_if_min", "add_if_max" -> processAddCommandInScript(commandNameFromScript, scriptInputManager, lineNumber)
                        else -> processSingleCommand(commandNameFromScript, if (parts.size > 1) parts.drop(1) else emptyList())
                    }
                }
            }
        } catch (e: Exception) {
            ioManager.error("Error during script execution '$fileName': ${e.message}")
            // e.printStackTrace() // Раскомментируй для детальной отладки
        } finally {
            ioManager.setInput(originalInputManager)
            executedScripts.remove(filePathString)
            recursionDepth--
            ioManager.outputLine("Finished executing script: $fileName (Depth: $recursionDepth)")
        }
    }

    private fun processAddCommandInScript(commandNameInLowercase: String, scriptInputManager: InputManager, baseLineNumber: Int) {
        val vehicleData = mutableListOf<String>()
        ioManager.outputLine("[Script Processing '$commandNameInLowercase'] Expecting 7 lines of vehicle data...")
        var linesRead = 0
        for (i in 1..7) {
            val dataLine = scriptInputManager.readLine()
            if (dataLine == null) {
                ioManager.error("[Script Error Line ${baseLineNumber + linesRead + 1}] Unexpected end of script. Expected data for '$commandNameInLowercase'.")
                return
            }
            vehicleData.add(dataLine.trim())
            linesRead++
        }

        if (linesRead < 7) { // Дополнительная проверка, хотя цикл должен обеспечить 7 чтений или ошибку
            ioManager.error("[Script ERROR] Not enough data lines for '$commandNameInLowercase' command. Expected 7, got $linesRead.")
            return
        }

        try {
            // ioManager.outputLine("[Script Processing '$commandNameInLowercase'] Data collected: $vehicleData")
            val vehicleFromScript = vehicleReader.readVehicleFromScript(vehicleData)

            // Находим "сырое" имя команды с "+" для отправки на сервер
            val commandNameToSend = rawServerCommands.firstOrNull {
                it.removeSuffix("+").equals(commandNameInLowercase, ignoreCase = true)
            } ?: "$commandNameInLowercase+" // Если вдруг не нашли, формируем с "+"

            val request = Request(
                body = listOf(commandNameToSend), // Только имя команды (с "+")
                vehicle = vehicleFromScript,
                currentCommandsList = ArrayList(rawServerCommands)
            )
            ioManager.outputLine("Sending command '$commandNameToSend' from script to server...")
            val response = apiClient.sendRequestAndWaitForResponse(request)
            if (response != null) {
                ioManager.outputLine("[Script INFO] '$commandNameToSend' command response: ${response.responseText}")
                // Обновление списка команд произойдет через callback, если сервер его прислал
            } else {
                ioManager.error("[Script ERROR] No response for '$commandNameToSend' command from script or request timed out.")
            }
        } catch (e: Exception) {
            ioManager.error("[Script ERROR] Error processing '$commandNameInLowercase' command from script: ${e.message}. Data was: $vehicleData")
            // e.printStackTrace() // Раскомментируй для детальной отладки
        }
    }
}