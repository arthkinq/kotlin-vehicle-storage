package org.example.commands

import org.example.IO.IOManager
import org.example.core.CollectionManager
import org.example.core.Response
import org.example.model.Vehicle
import java.nio.file.Path

class SaveCommand : Command(
    name = "save",
    description = "Save the collection to a file.",
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
        val errors = if (args.isEmpty()) {
            collectionManager.saveToFile()
        } else {
            emptyList()
        }

        return if (errors.isEmpty()) {
            Response("Data saved successfully.")
        } else {
            Response("Error saving data: ${errors.joinToString("; ")}")
        }


    }
}