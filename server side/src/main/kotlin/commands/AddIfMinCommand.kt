package org.example.commands

import org.example.IO.IOManager
import org.example.core.CollectionManager
import org.example.core.Request
import org.example.core.Response
import org.example.core.VehicleReader
import org.example.model.Vehicle

class AddIfMinCommand(private val reader: VehicleReader) : Command(
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
            return Response("Error: Args can be size ${size}.")
        }
        val newVehicle = reader.readVehicle()
        val minVehicle = collectionManager.getMin()
        if (minVehicle == null || minVehicle > newVehicle) {
            collectionManager.addVehicle(newVehicle)
            return Response("Vehicle added with ID: ${newVehicle.id}")

        } else {
            return Response("New vehicle's value is not less then the value of the smallest item in that collection.")
        }
    }
}