package commands

import io.IOManager
import core.CollectionManager
import common.Response
import model.Vehicle

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
        val vehicletmp = collectionManager.findByCharacteristic(args[0], args[1])
            ?: return Response("No vehicle found with $args[0] = $args[1]")
        collectionManager.deleteElement(vehicletmp)
        return Response("Element removed: $vehicletmp")
    }
}