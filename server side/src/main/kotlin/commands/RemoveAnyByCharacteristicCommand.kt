package org.example.commands

import org.example.IO.IOManager
import org.example.core.CollectionManager
import org.example.core.Response
import org.example.model.Vehicle

abstract class RemoveAnyByCharacteristicCommand(
    name: String,
    description: String,
    size: Int
) : Command(
    name = name,
    description = description,
    size = size
) {
    override fun execute(
        args: List<String>,
        collectionManager: CollectionManager,
        ioManager: IOManager,
        vehicle: Vehicle?
    ): Response {
        if (args.isEmpty() || args.size != 2) {
            return Response("Error: Args can be size ${size}.")
        }
        val vehicle = collectionManager.findByCharacteristic(args[0], args[1])
            ?: return Response("No vehicle found with $args[0] = $args[1]")
        collectionManager.deleteElement(vehicle)
        return Response("Element removed: $vehicle")
    }
}