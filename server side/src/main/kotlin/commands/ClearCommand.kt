package commands

import myio.IOManager
import core.CollectionManager
import common.CommandArgument
import common.Response
import core.VehicleService
import model.Vehicle

class ClearCommand : Command(
    name = "clear",
    description = "Clear collection.",
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
        if(userId != null) {
            vehicleService.clearUserVehicles(userId)
            return Response("Collection is clear.")
        }
        return Response("Error: UserID can't be empty.")
    }

    override fun getExpectedArguments(): List<CommandArgument> {
        return emptyList()
    }

    override fun doesRequireVehicle(): Boolean {
        return false
    }
}
