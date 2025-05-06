package org.example.commands

import org.example.IO.IOManager
import org.example.core.CollectionManager
import org.example.core.Response
import org.example.model.Vehicle

abstract class FilterByCharacteristicCommand (
    name: String,
    description: String,
    size: Int) : Command (
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
        if (!checkSizeOfArgs(args.size)) {
            return Response("Error: Args can be size ${size}.")

        }
        val vehicles = collectionManager.filterByCharacteristic(args[0], args[1])
        return if(vehicles.isEmpty())  {
            Response("No vehicles found with $args[0] = $args[1]")

        } else{
            Response("Vehicles with $args[0] = $args[1]: ЗДЕСЬ ДОЛЖНЫ БЫТЬ ВЕХИКЛЕС")
        }

    }
}