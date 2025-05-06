package org.example.commands

import org.example.IO.IOManager
import org.example.core.CollectionManager
import org.example.core.Response
import org.example.core.VehicleReader
import org.example.model.Vehicle


class UpdateIdCommand(
    private val reader: VehicleReader
) : Command(
    name = "update_id",
    description = "Update the element value by id.",
    size = 1
) {
    override fun execute(
        args: List<String>,
        collectionManager: CollectionManager,
        ioManager: IOManager,
        vehicle: Vehicle?
    ): Response {
        if (!checkSizeOfArgs(args.size)) {
            return Response("Error: Args can be size ${size}.")
        }
        val id = args[0].toInt()
        val vehicle: Vehicle = collectionManager.getById(id)
            ?: return Response("Can not find vehicle by $id. Your collection' max id = ${collectionManager.size() - 1}.")
        reader.readUpdatesForVehicle(vehicle)
        return Response("Vehicle $id was updated.")

    }
}
