package core

import io.IOManager // Используется для ioManagerForLogging
import commands.*
import common.CommandDescriptor
import common.Response
import model.Vehicle // Модель данных
import java.util.logging.Level
import java.util.logging.Logger

class CommandProcessor(
    private val ioManagerForLogging: IOManager, // Для логирования ошибок и информации на сервере
    fileName: String // Имя файла для CollectionManager
) {
    private val commandsList: Map<String, CommandInterface>
    val collectionManager = CollectionManager(fileName)
    private val logger = Logger.getLogger(CommandProcessor::class.java.name)

    init {
        commandsList = loadCommandsList()
        logger.log(
            Level.INFO, "CommandProcessor initialized. CollectionManager loaded with ${collectionManager.size()} items."
        )
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

        mutableCommands["add"] = AddCommand()
        mutableCommands["add_if_max"] = AddIfMaxCommand()
        mutableCommands["add_if_min"] = AddIfMinCommand()
        mutableCommands["update_id"] = UpdateIdCommand()

        mutableCommands["help"] = HelpCommand(this)

        logger.log(Level.INFO, "Available commands loaded: ${mutableCommands.keys.joinToString(", ")}")
        return mutableCommands.toMap()
    }

    /**
     * Обрабатывает команду, полученную от клиента.
     * @param commandBody Список строк, где первый элемент - имя команды, остальные - аргументы.
     * @param vehicleFromRequest Объект Vehicle, если команда его требует (например, add, update_id).
     * @return Объект Response с результатом выполнения команды.
     */
    fun processCommand(commandBody: List<String>, vehicleFromRequest: Vehicle?): Response {
        if (commandBody.isEmpty()) {
            logger.log(Level.WARNING, "CommandProcessor: Received empty command body.")
            return Response("Error: Empty command received by server.")
        }

        val commandName = commandBody[0].lowercase()
        val commandArgs = commandBody.drop(1)

        logger.log(Level.INFO, "CommandProcessor: Processing command '$commandName' with args: $commandArgs")
        if (vehicleFromRequest != null) {
            logger.log(Level.INFO, "CommandProcessor: Vehicle data received: $vehicleFromRequest")
        }


        val command = commandsList[commandName] ?: run {
            logger.log(Level.WARNING, "CommandProcessor: Unknown command '$commandName'.")
            return Response("Error: Unknown command '$commandName' on server.")
        }

        if (command.doesRequireVehicle() && vehicleFromRequest == null) {
            logger.log(
                Level.WARNING,
                "CommandProcessor: Command '$commandName' requires a Vehicle object, but none was provided."
            )
            return Response("Error: Command '$commandName' requires vehicle data, but it was not sent by the client.")
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
            logger.log(
                Level.SEVERE,
                "CommandProcessor: Critical error executing command '$commandName': ${e.message}\n${e.stackTraceToString()}"
            )
            Response("Error: An unexpected server error occurred while executing command '$commandName'.")
        }
    }

    fun getCommandDescriptors(): List<CommandDescriptor> {
        return commandsList.map { (name, command) ->
            CommandDescriptor(
                name = name,
                description = command.getDescription(),
                arguments = command.getExpectedArguments(),
                requiresVehicleObject = command.doesRequireVehicle()
            )
        }.sortedBy { it.name }
    }
}