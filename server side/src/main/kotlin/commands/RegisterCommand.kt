package commands

import common.ArgumentType
import common.CommandArgument
import common.CommandDescriptor
import common.Response
import core.VehicleService
import db.UserDAO
import model.Vehicle
import myio.IOManager

class RegisterCommand(private val userDAO: UserDAO) : CommandInterface, AuthCommandInterface {
    override fun getName(): String = "register_command"
    override fun getDescription(): String = "register a new user. Usage: register <username> <password>"
    override fun getExpectedArguments(): List<CommandArgument> = listOf(
        CommandArgument("username", ArgumentType.STRING, description = "Your desired username"),
        CommandArgument("password", ArgumentType.STRING, description = "Your desired password")
    )
    override fun doesRequireVehicle(): Boolean = false
    override fun execute(
        args: List<String>,
        vehicleService: VehicleService,
        ioManager: IOManager,
        vehicle: Vehicle?,
        userId: Int?
    ): Response {
        return Response("Error: Use register <username> <password> format through client.")
    }

    override fun execute(username: String, plainPasswordStr: String, ioManager: IOManager): Response {
        if (username.isBlank() || plainPasswordStr.isBlank()) {
            return Response("Error: Username and password cannot be empty.")
        }
        val newUser = userDAO.addUser(username, plainPasswordStr)
        return if (newUser != null) {
            Response("User '$username' registered successfully. You can now login.")
        } else {
            Response("Error: Registration failed. Username might already be taken or an internal error occurred.")
        }
    }
}
