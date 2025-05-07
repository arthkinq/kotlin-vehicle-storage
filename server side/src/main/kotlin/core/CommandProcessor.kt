package org.example.core

import org.example.IO.IOManager // Используется для ioManagerForLogging
import org.example.commands.*
import org.example.model.Vehicle // Модель данных
import java.util.logging.Level
import java.util.logging.Logger

class CommandProcessor(
    private val ioManagerForLogging: IOManager, // Для логирования ошибок и информации на сервере
    fileName: String // Имя файла для CollectionManager
) {

    // Рекурсия для execute_script обрабатывается на стороне клиента.
    // Если бы execute_script выполнялся на сервере (например, скрипт лежит на сервере),
    // то эти поля были бы здесь актуальны. В текущей архитектуре они не используются сервером.
    // private val maxRecursionDepth = 5
    // private var recursionDepth = 0
    // private val executedScripts = mutableSetOf<String>()

    private val commandsList: Map<String, CommandInterface>
    val collectionManager = CollectionManager(fileName) // Управляет коллекцией
    private val logger = Logger.getLogger(ApiServer::class.java.name)

    init {
        // Инициализация CollectionManager происходит при его создании (загрузка из файла)
        commandsList = loadCommandsList()
        logger.log(
            Level.INFO,
            "CommandProcessor initialized. CollectionManager loaded with ${collectionManager.size()} items."
        )
    }

    private fun loadCommandsList(): Map<String, CommandInterface> {
        val mutableCommands = mutableMapOf<String, CommandInterface>()

        // Команды, не требующие объекта Vehicle от клиента или работающие с аргументами
        mutableCommands["clear"] = ClearCommand()
        mutableCommands["filter_by_engine_power"] = FilterByEnginePowerCommand()
        mutableCommands["info"] = InfoCommand()
        mutableCommands["min_by_name"] = MinByNameCommand()
        mutableCommands["remove_any_by_engine_power"] = RemoveAnyByEnginePowerCommand()
        mutableCommands["remove_by_id"] = RemoveByIdCommand()
        mutableCommands["remove_first"] = RemoveFirstCommand()
        mutableCommands["show"] = ShowCommand()

        // Команды, которые ожидают объект Vehicle в Request.vehicle
        mutableCommands["add"] = AddCommand()
        mutableCommands["add_if_max"] = AddIfMaxCommand()
        mutableCommands["add_if_min"] = AddIfMinCommand()
        mutableCommands["update_id"] = UpdateIdCommand() // Также ожидает ID в аргументах

        // HelpCommand передает себе текущий список команд для отображения
        mutableCommands["help"] =
            HelpCommand(mutableCommands.toMap()) // Передаем копию, чтобы избежать проблем с изменением

        logger.log(Level.INFO,"Available commands loaded: ${mutableCommands.keys.joinToString(", ")}")
        return mutableCommands.toMap() // Возвращаем неизменяемую карту
    }

    /**
     * Обрабатывает команду, полученную от клиента.
     * @param commandBody Список строк, где первый элемент - имя команды, остальные - аргументы.
     * @param vehicleFromRequest Объект Vehicle, если команда его требует (например, add, update_id).
     * @return Объект Response с результатом выполнения команды.
     */
    fun processCommand(commandBody: List<String>, vehicleFromRequest: Vehicle?): Response {
        if (commandBody.isEmpty()) {
            logger.log(Level.WARNING,"CommandProcessor: Received empty command body.")
            return Response("Error: Empty command received by server.")
        }

        val commandName = commandBody[0]
        val commandArgs = commandBody.drop(1) // Остальные элементы - аргументы

        logger.log(Level.INFO,"CommandProcessor: Processing command '$commandName' with args: $commandArgs")
        if (vehicleFromRequest != null) {
            logger.log(Level.INFO,"CommandProcessor: Vehicle data received: $vehicleFromRequest")
        }


        val command = commandsList[commandName]
            ?: run {
                logger.log(Level.WARNING,"CommandProcessor: Unknown command '$commandName'.")
                return Response("Error: Unknown command '$commandName' on server.")
            }

        return try {
            // Выполнение команды
            // ioManagerForLogging передается в команду, если ей нужно что-то логировать или выводить на сервере
            // (например, команда save может сообщить о результате сохранения в консоль сервера)
            command.execute(
                args = commandArgs,
                collectionManager = collectionManager,
                ioManager = ioManagerForLogging, // Для логирования внутри команды
                vehicle = vehicleFromRequest     // Передаем объект Vehicle
            )
        } catch (e: Exception) {
            // Перехватываем любые неожиданные ошибки при выполнении команды
            logger.log(Level.SEVERE,"CommandProcessor: Critical error executing command '$commandName': ${e.message}\n${e.stackTraceToString()}")
            Response("Error: An unexpected server error occurred while executing command '$commandName'.")
        }
    }

    fun getAvailableCommandNames(): List<String> {
        return commandsList.keys.sorted()
    }
}