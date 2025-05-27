package core

import myio.IOManager // Используется для ioManagerForLogging
import commands.*
import common.CommandDescriptor
import common.Request
import common.Response
import db.UserDAO
import db.VehicleDAO
import model.User
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

class CommandProcessor(
    private val ioManagerForLogging: IOManager
) {
    private val commandsList: Map<String, CommandInterface>
    private val userDAO = UserDAO()
    private val vehicleDAO = VehicleDAO()
    val vehicleService = VehicleService(vehicleDAO)
    private val logger = Logger.getLogger(CommandProcessor::class.java.name)
    private val activeUserSessions = ConcurrentHashMap<SocketChannel, User>()
    init {
        commandsList = loadCommandsList()
        logger.log(
            Level.INFO, "CommandProcessor initialized. VehicleService loaded with ${vehicleService.size()} items."
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
        mutableCommands["register"] = RegisterCommand(userDAO)
        mutableCommands["login"] = LoginCommand(userDAO)

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
    fun processCommand(request: Request): Response { // Modified to take full Request
        val commandBody = request.body
        val vehicleFromRequest = request.vehicle
        val username = request.username
        val password = request.password

        if (commandBody.isEmpty()) {
            logger.log(Level.WARNING, "CommandProcessor: Received empty command body.")
            return Response("Error: Empty command received by server.")
        }

        val commandName = commandBody[0].lowercase()
        val commandArgs = commandBody.drop(1)

        logger.log(Level.INFO, "CommandProcessor: Processing command '$commandName' for user '$username' with args: $commandArgs")

        val command = commandsList[commandName] ?: run {
            logger.log(Level.WARNING, "CommandProcessor: Unknown command '$commandName'.")
            return Response("Error: Unknown command '$commandName' on server.")
        }

        if (command is RegisterCommand || command is LoginCommand) {
            if (username == null || password == null) {
                return Response("Error: Username and password are required for $commandName.")
            }

            return (command as AuthCommandInterface).execute(username, password, ioManagerForLogging)
        }

        if (username == null || password == null) {
            return Response("Error: Authentication required. Please login or register. (Missing credentials)")
        }

        val user = userDAO.findUserByUsername(username)
        if (user == null || !userDAO.verifiPassword(user, password)) {
            logger.warning("Authentication failed for user '$username'.")
            return Response("Error: Authentication failed. Invalid username or password.")
        }
        logger.info("User '$username' (ID: ${user.id}) authenticated successfully.")


        if (command.doesRequireVehicle() && vehicleFromRequest == null) {
            logger.log(Level.WARNING, "CommandProcessor: Command '$commandName' requires a Vehicle object, but none was provided.")
            return Response("Error: Command '$commandName' requires vehicle data, but it was not sent by the client.")
        }

        return try {
            command.execute(
                args = commandArgs,
                vehicleService = vehicleService,
                ioManager = ioManagerForLogging,
                vehicle = vehicleFromRequest,
                userId = user.id
            )
        } catch (e: SecurityException) {
            logger.log(Level.WARNING, "Authorization failed for command '$commandName' by user '${user.username}': ${e.message}")
            Response("Error: Authorization failed. ${e.message}")
        }
        catch (e: Exception) {
            logger.log(Level.SEVERE, "CommandProcessor: Critical error executing command '$commandName': ${e.message}\n${e.stackTraceToString()}")
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