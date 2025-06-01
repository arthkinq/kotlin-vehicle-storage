package commands

import myio.IOManager
import common.ArgumentType
import common.CommandArgument
import common.Response
import core.VehicleService
import model.Vehicle

class RemoveAnyByEnginePowerCommand : RemoveAnyByCharacteristicCommand(
    name = "remove_any_by_engine_power",
    description = "Remove one element whose enginePower matches the specified value.",
    size = 1
) {
    override fun execute(
        args: List<String>,
        vehicleService: VehicleService,
        ioManager: IOManager,
        vehicle: Vehicle?,
        userId: Int?
    ): Response {
        if(!checkSizeOfArgs(args.size)) {
            return Response("Error: Args can be size ${size}.")
        }
         return super.execute(listOf("enginePower", args[0]), vehicleService, ioManager, null,userId)
    }
    override fun getExpectedArguments(): List<CommandArgument> {
        return listOf(CommandArgument("engine_power", ArgumentType.DOUBLE, isOptional = false))
    }

    override fun doesRequireVehicle(): Boolean {
        return false
    }
}