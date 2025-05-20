package commands

import myio.IOManager
import core.CollectionManager
import common.CommandArgument
import common.Response
import model.Vehicle

class AddCommand : Command(
    name = "add",
    description = "Add new vehicle to collection",
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
            ioManager.error("AddCommand: Vehicle object is null in the request.")
            return Response("Error: Vehicle data is missing in the request for 'add' command.")
        }

        try {
            val addedVehicle = collectionManager.addVehicle(vehicle)
            return Response("Vehicle added successfully with ID: ${addedVehicle.id}")
        } catch (e: IllegalArgumentException) {
            ioManager.error("AddCommand: Error adding vehicle - ${e.message}")
            return Response("Error adding vehicle: ${e.message}")
        } catch (e: Exception) {
            ioManager.error("AddCommand: Unexpected error adding vehicle - ${e.message}")
            return Response("An unexpected error occurred while adding the vehicle.")
        }
    }

    override fun getExpectedArguments(): List<CommandArgument> {
        return emptyList()
    }

    override fun doesRequireVehicle(): Boolean {
        return true
    }
}