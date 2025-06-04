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

        val allVehicles = vehicleService.getAll().sortedBy { it.name }
        return if (allVehicles.isEmpty()) {
            Response("Collection is empty.", vehicles = emptyList())
        } else {
            Response(
                responseText = "Successfully retrieved ${allVehicles.size} vehicle(s).",
                vehicles = allVehicles
            )
        }
    }

    override fun getExpectedArguments(): List<CommandArgument> {
        return emptyList()
    }

    override fun doesRequireVehicle(): Boolean {
        return false
    }
}