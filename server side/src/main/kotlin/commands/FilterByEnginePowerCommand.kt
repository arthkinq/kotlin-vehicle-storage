package commands

import myio.IOManager
import common.ArgumentType
import core.CollectionManager
import common.CommandArgument
import common.Response
import model.Vehicle

class FilterByEnginePowerCommand : FilterByCharacteristicCommand(
    name = "filter_by_engine_power",
    description = "Find all the elements with the specified engine power value.",
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
        return super.execute(listOf("enginePower", args[0]), collectionManager, ioManager, null)


    }

    override fun getExpectedArguments(): List<CommandArgument> {
        return listOf(
            CommandArgument(name = "engine_power", type = ArgumentType.DOUBLE, description = "The engine power value to filter by.")
        )
    }
    override fun doesRequireVehicle(): Boolean {
        return false
    }
}