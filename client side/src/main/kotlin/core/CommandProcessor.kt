package org.example.core // Предполагаемый пакет для клиентского CommandProcessor

import org.example.IO.IOManager
import org.example.IO.InputManager // Для processScriptFile
import org.example.model.Vehicle // Модель данных
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.InvalidPathException // Для обработки ошибок пути

class CommandProcessor(
    private val apiClient: ApiClient, // Новый неблокирующий ApiClient
    private val ioManager: IOManager
) {
    private val vehicleReader: VehicleReader = VehicleReader(ioManager) // Для чтения данных Vehicle

    // Настройки для execute_script (рекурсия)
    private val maxRecursionDepth = 5
    private var recursionDepth = 0
    private val executedScripts = mutableSetOf<String>() // Для отслеживания уже выполненных скриптов в текущей цепочке вызовов

    // Список команд, известных клиенту. Обновляется с сервера.
    private val listOfCommands = mutableListOf<String>()

    fun start() {
        ioManager.outputLine("Transport Manager Client v2.0 (NIO)")
        ioManager.outputLine("Attempting to fetch initial command list from server...")

        // 1. Начальная загрузка команд (обычно команда "help")
        try {
            val initialRequest = Request(body = listOf("help"), vehicle = null, currentCommandsList = null)
            val initialResponse = apiClient.sendRequestAndWaitForResponse(initialRequest)

            if (initialResponse != null) {
                ioManager.outputLine(initialResponse.responseText) // Вывод текста (например, help-информации)

                val serverNewCommands = initialResponse.newCommandsList
                if (serverNewCommands.isNotEmpty()) {
                    listOfCommands.clear()
                    listOfCommands.addAll(serverNewCommands)
                    ioManager.outputLine("Initial command list received from server.")
                } else if (!initialResponse.responseText.lowercase().contains("error")) {
                    ioManager.outputLine("Warning: No commands received in initial response, but connection was successful. Server might have no commands configured or an issue.")
                }

                if (listOfCommands.isEmpty()) {
                    if (initialResponse.responseText.lowercase().contains("error")) {
                        ioManager.error("Error fetching initial command list (server returned an error). Client will exit.")
                        return
                    }
                    ioManager.error("Initial command list from server is empty. Client will exit as it cannot function.")
                    return
                }
            } else {
                // initialResponse is null (таймаут или критическая ошибка соединения в ApiClient)
                ioManager.error("Failed to fetch initial command list from server (no response or timeout). Client will exit.")
                return
            }
        } catch (e: Exception) {
            ioManager.error("Critical error during initial command fetch: ${e.message}. Client will exit.")
            e.printStackTrace() // Для отладки
            return
        }

        // 2. Основной цикл приема команд от пользователя
        var continueExecution = true
        while (continueExecution) {
            ioManager.outputInline("> ")
            val userInput = ioManager.readLine().trim()
            val parts = userInput.split("\\s+".toRegex(), 2) // Разделяем на имя команды и остальное
            val commandName = parts.getOrNull(0) ?: ""
            val argument = parts.getOrNull(1) ?: ""

            when (commandName.lowercase()) { // Сравнение без учета регистра для удобства
                "exit" -> {
                    ioManager.outputLine("Exiting client application...")
                    continueExecution = false // Устанавливаем флаг для выхода из цикла
                }
                "execute_script" -> {
                    if (argument.isNotBlank()) {
                        executeScript(argument)
                    } else {
                        ioManager.outputLine("Usage: execute_script <filename>")
                    }
                }
                "" -> {
                    // Пустой ввод, ничего не делаем, ждем следующую команду
                }
                else -> {
                    // Для всех остальных команд вызываем processSingleCommand
                    // Передаем уже разделенные имя команды и аргументы (если они есть)
                    // или можно передавать всю строку userInput, если processSingleCommand сам парсит
                    processSingleCommand(commandName, if (parts.size > 1) parts.drop(1) else emptyList())
                }
            }
        }
        ioManager.outputLine("Client command loop finished.")
    }

    /**
     * Обрабатывает одну команду, введенную пользователем или из скрипта (кроме execute_script и exit).
     */
    private fun processSingleCommand(commandName: String, args: List<String>) {
        // Проверяем, известна ли команда клиенту
        if (!listOfCommands.any { it.equals(commandName, ignoreCase = true) }) {
            ioManager.outputLine("Command '$commandName' is not recognized by the client. Type 'help' for available commands.")
            return
        }

        var vehicleForRequest: Vehicle? = null
        // finalCommandBody будет состоять из имени команды (в правильном регистре с сервера) и ее аргументов
        val actualCommandNameFromServer = listOfCommands.first { it.equals(commandName, ignoreCase = true) }
        var finalCommandBody: List<String> = listOf(actualCommandNameFromServer) + args

        // Специальная логика для команд, требующих Vehicle или интерактивного ввода аргументов
        when (actualCommandNameFromServer) { // Используем имя команды с сервера для точности
            "add", "add_if_max", "add_if_min" -> {
                ioManager.outputLine("Please enter data for the new vehicle:")
                vehicleForRequest = vehicleReader.readVehicle()
                // Для этих команд аргументы в строке не нужны, Vehicle передается отдельно
                finalCommandBody = listOf(actualCommandNameFromServer)
            }
            "update_id" -> {
                val idArg = if (args.isNotEmpty()) args[0] else {
                    ioManager.outputLine("Usage: update_id <ID_to_update>")
                    ioManager.outputInline("Enter ID of vehicle to update: ")
                    val idStr = ioManager.readLine()
                    if (idStr.isBlank()) {
                        ioManager.error("ID is required for 'update_id' command.")
                        return
                    }
                    idStr
                }
                val idToUpdate = idArg.toIntOrNull()
                if (idToUpdate == null) {
                    ioManager.error("Invalid ID format '$idArg' for 'update_id'. ID must be an integer.")
                    return
                }

                ioManager.outputLine("Enter new data for vehicle with ID $idToUpdate:")
                val newVehicleDataFromReader = vehicleReader.readVehicle() // Читаем все поля
                vehicleForRequest = Vehicle( // Собираем объект для отправки
                    id = idToUpdate, // Указываем ID обновляемого объекта
                    name = newVehicleDataFromReader.name,
                    coordinates = newVehicleDataFromReader.coordinates,
                    creationDate = 0L, // Сервер должен игнорировать это и сохранить существующую дату
                    enginePower = newVehicleDataFromReader.enginePower,
                    distanceTravelled = newVehicleDataFromReader.distanceTravelled,
                    type = newVehicleDataFromReader.type,
                    fuelType = newVehicleDataFromReader.fuelType
                )
                // Сервер ожидает ID в аргументах команды
                finalCommandBody = listOf(actualCommandNameFromServer, idToUpdate.toString())
            }
            // Другие команды, если им нужна специальная подготовка аргументов
            // Например, "remove_by_id <id>" или "filter_by_engine_power <power>"
            // уже будут иметь свои аргументы в 'args', и finalCommandBody сформируется корректно.
        }

        // Создание запроса
        val request = Request(
            body = finalCommandBody,
            vehicle = vehicleForRequest,
            currentCommandsList = ArrayList(listOfCommands) // Передаем копию на случай, если серверу это нужно
        )

        ioManager.outputLine("Sending command '$actualCommandNameFromServer' to server...")
        val response = apiClient.sendRequestAndWaitForResponse(request) // Отправка и ожидание ответа

        if (response != null) {
            ioManager.outputLine(response.responseText) // Вывод основного текста ответа

            val serverNewCommands = response.newCommandsList
            if (serverNewCommands.isNotEmpty()) {
                // Обновляем список команд, если он изменился
                if (listOfCommands != serverNewCommands) {
                    listOfCommands.clear()
                    listOfCommands.addAll(serverNewCommands)
                    ioManager.outputLine("Client command list updated from server.")
                }
            }
        } else {
            // response is null (таймаут или ошибка соединения/NIO в ApiClient)
            ioManager.error("No response received from server or request timed out for command: $actualCommandNameFromServer")
        }
    }

    private fun executeScript(fileName: String) {
        if (recursionDepth >= maxRecursionDepth) {
            ioManager.error("Max script recursion depth ($maxRecursionDepth) exceeded. Aborting script '$fileName'.")
            return
        }

        val filePathString = Paths.get(fileName).toAbsolutePath().toString()
        if (filePathString in executedScripts) {
            ioManager.error("Recursion detected: Script '$fileName' (resolved to '$filePathString') is already running in this call chain. Aborting.")
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
        executedScripts.add(filePathString) // Добавляем абсолютный путь для более надежного отслеживания
        ioManager.outputLine("Executing script: $fileName (Depth: $recursionDepth)")

        val originalInputManager = ioManager.getInput() // Сохраняем текущий менеджер ввода
        try {
            Files.newBufferedReader(path).use { reader ->
                val scriptInputManager = object : InputManager {
                    override fun readLine(): String? = reader.readLine()
                    override fun hasInput(): Boolean = reader.ready() // Проверяет, есть ли еще что читать
                }
                ioManager.setInput(scriptInputManager) // Устанавливаем чтение из файла

                var lineNumber = 0
                while (scriptInputManager.hasInput()) { // Используем hasInput из нашего scriptInputManager
                    lineNumber++
                    val line = scriptInputManager.readLine()?.trim()
                    if (line == null) break // Конец файла
                    if (line.isEmpty() || line.startsWith("#")) { // Пропускаем пустые строки и комментарии
                        continue
                    }

                    ioManager.outputLine("[Script Line $lineNumber]> $line")
                    val parts = line.split("\\s+".toRegex(), 2)
                    val commandName = parts.getOrNull(0) ?: ""
                    val argument = parts.getOrNull(1) ?: ""

                    // Обработка команд из скрипта
                    // 'exit' в скрипте обычно игнорируется или прерывает только скрипт
                    // 'execute_script' обрабатывается рекурсивно
                    when (commandName.lowercase()) {
                        "execute_script" -> {
                            if (argument.isNotBlank()) {
                                executeScript(argument) // Рекурсивный вызов
                            } else {
                                ioManager.error("[Script Error Line $lineNumber] Usage: execute_script <filename>")
                            }
                        }
                        "add", "add_if_min", "add_if_max" -> processAddCommandInScript(commandName, scriptInputManager, lineNumber)
                        // Другие команды обрабатываются стандартно
                        else -> processSingleCommand(commandName, if (parts.size > 1) parts.drop(1) else emptyList())
                    }
                    // Небольшая пауза, чтобы вывод не был слишком быстрым (опционально)
                    // Thread.sleep(50)
                }
            }
        } catch (e: Exception) {
            ioManager.error("Error during script execution '$fileName': ${e.message}")
            e.printStackTrace()
        } finally {
            ioManager.setInput(originalInputManager) // Восстанавливаем оригинальный менеджер ввода
            executedScripts.remove(filePathString)
            recursionDepth--
            ioManager.outputLine("Finished executing script: $fileName (Depth: $recursionDepth)")
        }
    }

    private fun processAddCommandInScript(commandName: String, scriptInputManager: InputManager, baseLineNumber: Int) {
        val vehicleData = mutableListOf<String>()
        ioManager.outputLine("[Script Processing '$commandName'] Expecting 7 lines of vehicle data...")
        var linesRead = 0
        for (i in 1..7) {
            if (!scriptInputManager.hasInput()) {
                ioManager.error("[Script Error Line ${baseLineNumber + i}] Unexpected end of script. Expected data for '$commandName'.")
                return
            }
            val dataLine = scriptInputManager.readLine()
            if (dataLine == null) { // Неожиданный конец файла
                ioManager.error("[Script Error Line ${baseLineNumber + i}] Unexpected end of script file while reading data for '$commandName'.")
                return
            }
            vehicleData.add(dataLine.trim()) // Добавляем даже пустые строки, чтобы сохранить структуру
            linesRead++
        }

        if (linesRead == 7) {
            try {
                ioManager.outputLine("[Script Processing '$commandName'] Data collected: $vehicleData")
                val vehicleFromScript = vehicleReader.readVehicleFromScript(vehicleData) // vehicleReader должен уметь это
                val request = Request(
                    body = listOf(commandName), // Используем оригинальное имя команды
                    vehicle = vehicleFromScript,
                    currentCommandsList = ArrayList(listOfCommands)
                )

                val response = apiClient.sendRequestAndWaitForResponse(request)
                if (response != null) {
                    ioManager.outputLine("[Script INFO] '$commandName' command response: ${response.responseText}")
                    val serverNewCommands = response.newCommandsList
                    if (serverNewCommands.isNotEmpty() && listOfCommands != serverNewCommands) {
                        listOfCommands.clear()
                        listOfCommands.addAll(serverNewCommands)
                        ioManager.outputLine("[Script INFO] Client command list updated from server.")
                    }
                } else {
                    ioManager.error("[Script ERROR] No response for '$commandName' command from script or request timed out.")
                }
            } catch (e: Exception) { // Ошибки при чтении из vehicleReader.readVehicleFromScript или другие
                ioManager.error("[Script ERROR] Error processing '$commandName' command from script: ${e.message}. Data was: $vehicleData")
                e.printStackTrace()
            }
        } else {
            // Эта ветка не должна достигаться при текущей логике цикла for, но для безопасности
            ioManager.error("[Script ERROR] Not enough data lines for '$commandName' command. Expected 7, got $linesRead.")
        }
    }
}