package commands

import io.IOManager
import core.CollectionManager
import common.CommandArgument
import common.Response
import model.Vehicle

class ClearCommand : Command(
    name = "clear",
    description = "Clear collection.",
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
        collectionManager.clear()
        return Response("Collection is clear.")
    }

    override fun getExpectedArguments(): List<CommandArgument> {
        return emptyList()
    }

    override fun doesRequireVehicle(): Boolean {
        return false
    }
}
