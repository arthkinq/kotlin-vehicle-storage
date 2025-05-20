package commands

import io.IOManager
import common.ArgumentType
import core.CollectionManager
import common.CommandArgument
import common.Response
import model.Vehicle

class RemoveByIdCommand : RemoveAnyByCharacteristicCommand(
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
        if (!checkSizeOfArgs(args.size)) {
            return Response("Error: Args can be size ${size}.")
        }
        return super.execute(listOf("id", args[0]), collectionManager, ioManager, null)
    }

    override fun getExpectedArguments(): List<CommandArgument> {
        return listOf(CommandArgument("id", ArgumentType.INTEGER, isOptional = false))
    }

    override fun doesRequireVehicle(): Boolean {
        return false
    }
}