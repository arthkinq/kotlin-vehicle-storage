package commands

import myio.IOManager
import common.CommandArgument
import common.Response
import core.VehicleService
import model.Vehicle

class MinByNameCommand : MinByCharacteristicCommand(
    name = "min_by_name",
    description = "Find item with minimal name.",
    size = 0
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
        return super.execute(listOf("name"), vehicleService, ioManager,null, userId)
    }
    override fun getExpectedArguments(): List<CommandArgument> {
        return emptyList()
    }

    override fun doesRequireVehicle(): Boolean {
        return false
    }
}