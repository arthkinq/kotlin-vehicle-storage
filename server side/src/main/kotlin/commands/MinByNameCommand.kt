package org.example.commands

import org.example.IO.IOManager
import org.example.core.CollectionManager
import org.example.core.Response
import org.example.model.Vehicle

class MinByNameCommand : MinByCharacteristicCommand(
    name = "min_by_name",
    description = "Find item with minimal name.",
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
        return super.execute(listOf("name"), collectionManager, ioManager,null)
    }
}