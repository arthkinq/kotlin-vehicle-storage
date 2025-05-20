package commands

import io.IOManager
import core.CollectionManager
import common.CommandArgument
import common.Response
import model.Vehicle

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
    override fun getExpectedArguments(): List<CommandArgument> {
        return emptyList()
    }

    override fun doesRequireVehicle(): Boolean {
        return false
    }
}