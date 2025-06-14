package commands

import myio.IOManager
import common.Response
import core.VehicleService
import model.Vehicle

abstract class FilterByCharacteristicCommand(
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
        val vehicles = vehicleService.filterByCharacteristic(args[0], args[1])
        return if (vehicles.isEmpty()) {
            Response("No vehicles found with $args[0] = $args[1]")
        } else {
            var response = "Vehicles with ${args[0]} = ${args[1]} : "
            vehicles.forEach { item ->
                response += "\n"
                response += item
                response += "\n"
            }
            Response(response)
        }

    }
}