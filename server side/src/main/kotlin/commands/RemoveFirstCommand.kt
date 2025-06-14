package commands

import myio.IOManager
import common.CommandArgument
import common.Response
import core.VehicleService
import model.Vehicle

class RemoveFirstCommand : Command(
    name = "remove_first",
    description = "Delete a first item in collection.",
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
        if (vehicleService.isEmpty()) {
            return Response("No element in the collection.")

        }
        if(userId == null) {
            return Response("Please login to continue")
        }
        if(vehicleService.deleteByNumber(0, userId)) {
            return Response("First element removed.")
        }
        return Response("Can't remove first element.")
    }

    override fun getExpectedArguments(): List<CommandArgument> {
        return emptyList()
    }

    override fun doesRequireVehicle(): Boolean {
        return false
    }

}