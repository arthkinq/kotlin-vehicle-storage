package org.example.commands

import org.example.IO.IOManager
import org.example.core.CollectionManager
import org.example.core.Response
import org.example.core.VehicleReader
import org.example.model.Vehicle

class AddIfMaxCommand(private val reader: VehicleReader) : Command(
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
            return Response("Error: Args can be size ${size}.")
        }
        val newVehicle = reader.readVehicle()
        val maxVehicle = collectionManager.getMax()
        if (maxVehicle == null || maxVehicle < newVehicle) {
            collectionManager.addVehicle(newVehicle)
            return Response("Vehicle added with ID: ${newVehicle.id}")
        } else {
            return Response("New vehicle's value doesn't exceed the value of the largest item in that collection.")
        }
    }
}