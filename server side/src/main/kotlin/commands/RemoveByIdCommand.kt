package org.example.commands

import org.example.IO.IOManager
import org.example.core.CollectionManager
import org.example.core.Response
import org.example.model.Vehicle

class RemoveByIdCommand : RemoveAnyByCharacteristicCommand (
    name = "remove_by_id",
    description = "Delete an item from the collection by its id.",
    size = 1
) {
    override fun execute(
        args: List<String>,
        collectionManager: CollectionManager,
        ioManager: IOManager,
        vehicle: Vehicle?
    ): Response {
        if(!checkSizeOfArgs(args.size)) {
            return Response("Error: Args can be size ${size}.")
        }
        return super.execute(listOf("id", args[0]), collectionManager, ioManager,null)
    }
}