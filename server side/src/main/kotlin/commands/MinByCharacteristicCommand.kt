package commands

import io.IOManager
import core.CollectionManager
import common.Response
import model.Vehicle

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
        val tempVehicle = collectionManager.getMin(args[0]) ?: return Response("Error: Args can be size ${size}.")
        return Response("Element found with minimal $args[0]: $tempVehicle")
    }
}