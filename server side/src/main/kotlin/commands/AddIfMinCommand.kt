package commands

import io.IOManager
import core.CollectionManager
import common.CommandArgument
import common.Response
import model.Vehicle

class AddIfMinCommand : Command(
    name = "add_if_min",
    description = "Add a new item to the collection if its value is less than that of the smallest item in this collection.",
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
            ioManager.error("AddIfMinCommand: Vehicle object is null in the request.") // Лог на сервере
            return Response("Error: Vehicle data is missing in the request for '${getName()}' command.")
        }

        val minVehicleInCollection = collectionManager.getAll().minByOrNull { it.enginePower }


        if (minVehicleInCollection == null || vehicle < minVehicleInCollection) {
            try {
                val addedVehicle = collectionManager.addVehicle(vehicle)
                return Response("Vehicle (ID: ${vehicle.id}) added successfully as it's smaller than min. New ID: ${addedVehicle.id}")
            } catch (e: IllegalArgumentException) {
                ioManager.error("AddIfMinCommand: Error adding vehicle - ${e.message}")
                return Response("Error adding vehicle: ${e.message}")
            } catch (e: Exception) {
                ioManager.error("AddIfMinCommand: Unexpected error adding vehicle - ${e.message}")
                return Response("An unexpected error occurred while adding the vehicle.")
            }
        } else {
            return Response("New vehicle (ID: ${vehicle.id}) was not added. Its value is not less than the smallest item's value (Min ID: ${minVehicleInCollection.id}).")
        }
    }

    override fun getExpectedArguments(): List<CommandArgument> {
        return emptyList()
    }

    override fun doesRequireVehicle(): Boolean {
        return true
    }
}