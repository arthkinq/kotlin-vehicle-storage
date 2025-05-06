package org.example.commands

import org.example.IO.IOManager
import org.example.core.CollectionManager
import org.example.core.Response
import org.example.model.Vehicle

class RemoveFirstCommand : Command (
    name = "remove_first",
    description = "Delete a first item in collection.",
    size = 0
){
    override fun execute(
        args: List<String>,
        collectionManager: CollectionManager,
        ioManager: IOManager,
        vehicle: Vehicle?
    ): Response {
        if(!checkSizeOfArgs(args.size)) {
            return Response("Error: Args can be size ${size}.")
        }
        if(collectionManager.isEmpty()) {
            return Response("No element in the collection.")

        }
        collectionManager.deleteByNumber(0)
        return Response("First element removed.")


    }

}