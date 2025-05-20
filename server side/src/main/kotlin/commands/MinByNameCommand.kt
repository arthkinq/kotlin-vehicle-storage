package commands

import myio.IOManager
import core.CollectionManager
import common.CommandArgument
import common.Response
import model.Vehicle

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
    override fun getExpectedArguments(): List<CommandArgument> {
        return emptyList()
    }

    override fun doesRequireVehicle(): Boolean {
        return false
    }
}