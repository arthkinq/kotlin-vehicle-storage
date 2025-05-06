package org.example.commands

import org.example.IO.IOManager
import org.example.core.CollectionManager
import org.example.core.Response
import org.example.model.Vehicle

abstract class MinByCharacteristicCommand(
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
        val vehicle = collectionManager.getMin(args[0]) ?: return Response("Error: Args can be size ${size}.")
        return Response("Element found with minimal $args[0]: $vehicle")
    }
}