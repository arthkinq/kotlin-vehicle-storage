package commands

import myio.IOManager
import common.ArgumentType
import common.CommandArgument
import common.Response
import core.VehicleService
import model.Vehicle

class UpdateIdCommand : Command(
    name = "update_id",
    description = "Update the element's value by its ID using new provided data.",
    size = 1
) {
    override fun execute(
        args: List<String>,
        vehicleService: VehicleService,
        ioManager: IOManager,
        vehicle: Vehicle?,
        userId: Int?
    ): Response {
        if (!checkSizeOfArgs(args.size)) {
            return Response("Error: '${getName()}' command expects 1 argument (the ID of the vehicle to update).")
        }

        if (vehicle == null) {
            ioManager.error("UpdateIdCommand: Vehicle object with new data is null in the request.")
            return Response("Error: New vehicle data is missing in the request for '${getName()}' command.")
        }

        val idFromArgs = args[0].toIntOrNull()
        if (idFromArgs == null) {
            ioManager.error("UpdateIdCommand: Invalid ID format '${args[0]}'.")
            return Response("Error: Invalid ID format provided for update. ID must be an integer.")
        }
        if(userId == null) {
            Response("Please login to contunue")
        }
        try {
            val success = vehicleService.updateVehicleById(idFromArgs, vehicle, userId)
            return if (success) {
                Response("Vehicle with ID $idFromArgs was updated successfully.")
            } else {
                Response("Vehicle with ID $idFromArgs not found in the collection. Nothing updated.")
            }
        } catch (e: IllegalArgumentException) {
            ioManager.error("UpdateIdCommand: Error updating vehicle ID $idFromArgs - ${e.message}")
            return Response("Error updating vehicle: ${e.message}")
        } catch (e: Exception) {
            ioManager.error("UpdateIdCommand: Unexpected error updating vehicle ID $idFromArgs - ${e.message}")
            return Response("An unexpected error occurred while updating the vehicle.")
        }
    }

    override fun getExpectedArguments(): List<CommandArgument> {
        return listOf(CommandArgument("id", ArgumentType.INTEGER, isOptional = false, description = "update existing vehicle by id with new parameters"))
    }

    override fun doesRequireVehicle(): Boolean {
        return true
    }
}