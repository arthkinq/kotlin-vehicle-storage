package commands

import myio.IOManager
import common.CommandArgument
import common.Response
import core.VehicleService
import model.Vehicle

class AddIfMaxCommand : Command(
    name = "add_if_max",
    description = "Add a new item to a collection if its value exceeds the value of the largest item in that collection.",
    size = 0
) {

    override fun execute(
        args: List<String>,
        vehicleService: VehicleService,
        ioManager: IOManager,
        vehicle: Vehicle?,
        userId: Int?
    ): Response {
        if (!checkSizeOfArgs(args.size)) {
            return Response("Error: '${getName()}' command takes no string arguments when a vehicle object is provided.")
        }
        if (vehicle == null) return Response("Error: Vehicle data is required for add command.")
        if (userId == null) return Response("Error: User authentication required to add elements.")
        if (vehicle == null) {
            ioManager.error("AddIfMaxCommand: Vehicle object is null in the request.")
            return Response("Error: Vehicle data is missing in the request for '${getName()}' command.")
        }

        val maxExistingByEnginePower = vehicleService.getMax("enginePower")

        if (maxExistingByEnginePower == null || vehicle.enginePower > maxExistingByEnginePower.enginePower) {
            try {
                val addedVehicle = vehicleService.addVehicle(vehicle, userId)
                return Response("Vehicle added with new ID: ${addedVehicle?.id}")
            } catch (e: IllegalArgumentException) {
                ioManager.error("AddIfMax: Error adding: ${e.message}")
                return Response("Error adding vehicle: ${e.message}")
            } catch (e: Exception) {
                ioManager.error("AddIfMax: Unexpected error adding: ${e.message}")
                return Response("Unexpected server error during add.")
            }
        } else {
            return Response("Vehicle not added, engine power not greater than max.")
        }
    }

    override fun getExpectedArguments(): List<CommandArgument> {
        return emptyList()
    }

    override fun doesRequireVehicle(): Boolean {
        return true
    }
}