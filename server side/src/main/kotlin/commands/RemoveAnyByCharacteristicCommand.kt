package commands

import myio.IOManager
import common.Response
import core.VehicleService
import model.Vehicle

abstract class RemoveAnyByCharacteristicCommand(
    name: String,
    description: String,
    size: Int
) : Command(
    name = name,
    description = description,
    size = size
) {
    override fun execute(
        args: List<String>,
        vehicleService: VehicleService,
        ioManager: IOManager,
        vehicle: Vehicle?,
        userId: Int?
    ): Response {
        if (args.isEmpty() || args.size != 2) {
            return Response("Error: Args can be size ${size}.")
        }
        val vehicletmp = vehicleService.findByCharacteristic(args[0], args[1])
            ?: return Response("No vehicle found with $args[0] = $args[1]")
        if(userId == null) {
            return Response("Please login to continue")
        } else if (userId != vehicletmp.userId) {
            return Response("You can\'t delete someone else\'s object.")
        }
        vehicleService.removeVehicle(vehicletmp.id, userId)
        return Response("Element removed: $vehicletmp")
    }
}