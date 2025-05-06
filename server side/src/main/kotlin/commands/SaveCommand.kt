package org.example.commands

import org.example.IO.IOManager
import org.example.core.CollectionManager
import org.example.core.Response
import org.example.model.Vehicle
import java.nio.file.Path

class SaveCommand : Command(
    name = "save",
    description = "Save the collection to a file.",
    size = 1
) {
    override fun execute(
        args: List<String>,
        collectionManager: CollectionManager,
        ioManager: IOManager,
        vehicle: Vehicle?
    ): Response {
        if (!checkSizeOfArgs(args.size, size)) {
            return Response("Error: Args can be size ${size}.")
        }
        if (args.isEmpty()) {
            collectionManager.saveToFile()
        } else {
            collectionManager.saveToFile(Path.of(args[0]))
        }

        return Response("Data saved")
    }

    private fun checkSizeOfArgs(f: Int, s: Int): Boolean {
        return s >= f
    }
}