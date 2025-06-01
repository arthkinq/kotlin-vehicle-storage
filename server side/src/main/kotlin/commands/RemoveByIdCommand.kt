package commands

import myio.IOManager
import common.ArgumentType
import common.CommandArgument
import common.Response
import core.VehicleService
import model.Vehicle

class RemoveByIdCommand : RemoveAnyByCharacteristicCommand(
    name = "remove_by_id",
    description = "Delete an item from the collection by its id.",
    size = 1
) {
    override fun execute(
        args: List<String>,
        vehicleService: VehicleService,
        ioManager: IOManager,
        vehicle: Vehicle?,
        userId: Int?
    ): Response {
        if (!checkSizeOfArgs(args.size)) {
            return Response("Error: Args can be size ${size}.")
        }
        return super.execute(listOf("id", args[0]), vehicleService, ioManager, null, userId)
    }

    override fun getExpectedArguments(): List<CommandArgument> {
        return listOf(CommandArgument("id", ArgumentType.INTEGER, isOptional = false))
    }

    override fun doesRequireVehicle(): Boolean {
        return false
    }
}