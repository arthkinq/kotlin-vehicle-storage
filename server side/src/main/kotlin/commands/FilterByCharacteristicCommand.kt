package commands

import myio.IOManager
import core.CollectionManager
import common.Response
import model.Vehicle

abstract class FilterByCharacteristicCommand(
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
        val vehicles = collectionManager.filterByCharacteristic(args[0], args[1])
        return if (vehicles.isEmpty()) {
            Response("No vehicles found with $args[0] = $args[1]")
        } else {
            var response = "Vehicles with ${args[0]} = ${args[1]} : "
            vehicles.forEach { item ->
                response += "\n"
                response += item
                response += "\n"
            }
            Response(response)
        }

    }
}