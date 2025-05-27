package commands

import myio.IOManager
import core.CollectionManager
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
        vehicleService.deleteByNumber(0)
        return Response("First element removed.")


    }

    override fun getExpectedArguments(): List<CommandArgument> {
        return emptyList()
    }

    override fun doesRequireVehicle(): Boolean {
        return false
    }

}