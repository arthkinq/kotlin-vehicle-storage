package commands

import myio.IOManager
import common.CommandArgument
import common.Response
import core.VehicleService
import model.Vehicle // Импорт нужен, если Vehicle используется в сигнатуре execute

class ShowCommand : Command(
    name = "show",
    description = "Display all the items in the collection.",
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
            return Response("Error: '${getName()}' command takes no arguments.")
        }

        if (vehicleService.isEmpty()) {
            return Response("Collection is empty.")
        } else {
            val stringBuilder = StringBuilder("Vehicles in collection (sorted by name):\n")
            vehicleService.getAll()
                .sortedBy { it.name}
                .forEach { item ->
                    stringBuilder.append("$item\n")
                }
            return Response(stringBuilder.toString())
        }
    }

    override fun getExpectedArguments(): List<CommandArgument> {
        return emptyList()
    }

    override fun doesRequireVehicle(): Boolean {
        return false
    }
}