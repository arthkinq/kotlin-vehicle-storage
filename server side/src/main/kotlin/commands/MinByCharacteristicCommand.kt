package commands

import myio.IOManager
import core.CollectionManager
import common.Response
import core.VehicleService
import model.Vehicle

abstract class MinByCharacteristicCommand(
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
        val tempVehicle = vehicleService.getMin(args[0]) ?: return Response("Error: Args can be size ${size}.")
        return Response("Element found with minimal $args[0]: $tempVehicle")
    }
}