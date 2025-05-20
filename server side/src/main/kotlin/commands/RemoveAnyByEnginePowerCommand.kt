package commands

import io.IOManager
import common.ArgumentType
import core.CollectionManager
import common.CommandArgument
import common.Response
import model.Vehicle

class RemoveAnyByEnginePowerCommand : RemoveAnyByCharacteristicCommand(
    name = "remove_any_by_engine_power",
    description = "Remove one element whose enginePower matches the specified value.",
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
         return super.execute(listOf("enginePower", args[0]), collectionManager, ioManager, null)
    }
    override fun getExpectedArguments(): List<CommandArgument> {
        return listOf(CommandArgument("engine_power", ArgumentType.DOUBLE, isOptional = false))
    }

    override fun doesRequireVehicle(): Boolean {
        return false
    }
}