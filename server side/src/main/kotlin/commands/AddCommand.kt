package commands

import myio.IOManager
import core.CollectionManager
import common.CommandArgument
import common.Response
import core.VehicleService
import model.Vehicle

class AddCommand : Command(
    name = "add",
    description = "Add new vehicle to collection",
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
            return Response("Error: '${getName()}' command takes no string arguments when a vehicle object is provided.")
        }
        if (vehicle == null) return Response("Error: Vehicle data is required for add command.")
        if (userId == null) return Response("Error: User authentication required to add elements.")
        val addedVehicle = vehicleService.addVehicle(vehicle, userId)
        return if(addedVehicle != null) {
            Response("Vehicle '${addedVehicle.name}' (ID: ${addedVehicle.id}) added successfully by user $userId.")
        } else {
            Response("Error: Could not add vehicle to the collection.")
        }
    }

    override fun getExpectedArguments(): List<CommandArgument> {
        return emptyList()
    }

    override fun doesRequireVehicle(): Boolean {
        return true
    }
}