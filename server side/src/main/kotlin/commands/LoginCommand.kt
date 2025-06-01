package commands

import common.ArgumentType
import common.CommandArgument
import common.Response
import core.VehicleService
import db.UserDAO
import model.Vehicle
import myio.IOManager

class LoginCommand(private val userDAO: UserDAO) : CommandInterface, AuthCommandInterface {
    override fun getName(): String = "login"
    override fun getDescription(): String = "log in as an existing user. Usage: login <username> <password>"
    override fun getExpectedArguments(): List<CommandArgument> = listOf(
        CommandArgument("username", ArgumentType.STRING, description = "Your username"),
        CommandArgument("password", ArgumentType.STRING, description = "Your password")
    )
    override fun doesRequireVehicle(): Boolean = false

    override fun execute(
        args: List<String>,
        vehicleService: VehicleService,
        ioManager: IOManager,
        vehicle: Vehicle?,
        userId: Int?
    ): Response {
        return Response("Error: Use login <username> <password> format through client.")
    }

    override fun execute(username: String, plainPasswordStr: String, ioManager: IOManager): Response {
        val user = userDAO.findUserByUsername(username)
        if (user != null && userDAO.verifiPassword(user, plainPasswordStr)) {
            return Response("User '$username' logged in successfully.")
        } else {
            return Response("Error: Login failed. Invalid username or password.")
        }
    }
}
