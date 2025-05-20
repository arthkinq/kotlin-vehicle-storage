package commands

import io.IOManager
import core.CollectionManager
import common.CommandArgument
import common.Response
import model.Vehicle

class AddIfMaxCommand : Command(
    name = "add_if_max",
    description = "Add a new item to a collection if its value exceeds the value of the largest item in that collection.",
    size = 0
) {

    override fun execute(
        args: List<String>,
        collectionManager: CollectionManager,
        ioManager: IOManager,
        vehicle: Vehicle?
    ): Response {
        if (!checkSizeOfArgs(args.size)) {
            return Response("Error: '${getName()}' command takes no string arguments when a vehicle object is provided.")
        }

        if (vehicle == null) {
            ioManager.error("AddIfMaxCommand: Vehicle object is null in the request.")
            return Response("Error: Vehicle data is missing in the request for '${getName()}' command.")
        }

        val maxExistingByEnginePower = collectionManager.getAll().maxByOrNull { it.enginePower }

        if (maxExistingByEnginePower == null || vehicle.enginePower > maxExistingByEnginePower.enginePower) {
            // Add the vehicle
            try {
                val addedVehicle = collectionManager.addVehicle(vehicle) // 'vehicle' is from client
                return Response("Vehicle added with new ID: ${addedVehicle.id}")
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