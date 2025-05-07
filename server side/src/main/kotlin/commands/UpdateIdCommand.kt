package org.example.commands

import org.example.IO.IOManager
import org.example.core.CollectionManager
import org.example.core.Response
import org.example.model.Vehicle

class UpdateIdCommand : Command(
    name = "update_id",
    description = "Update the element's value by its ID using new provided data.",
    size = 1
) {
    override fun execute(
        args: List<String>,
        collectionManager: CollectionManager,
        ioManager: IOManager,
        vehicle: Vehicle?
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

        try {
            val success = collectionManager.updateVehicleById(idFromArgs, vehicle)
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
}