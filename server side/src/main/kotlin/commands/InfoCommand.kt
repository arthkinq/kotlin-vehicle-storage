package commands


import myio.IOManager
import common.CommandArgument
import common.Response
import core.VehicleService
import model.Vehicle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class InfoCommand : Command(
    name = "info",
    description = "Displays project information.",
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
        var response =
            "Info: \n Collection type: ${vehicleService.getAll()::class.simpleName} \n Amount of elements: ${vehicleService.size()} \n"

        if (vehicleService.getAll().isNotEmpty()) {
            val readableDate = Instant.ofEpochMilli(vehicleService.getAll().first().creationDate)
                .atZone(ZoneId.of("UTC"))
                .toLocalDateTime()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            response += "Creation date: $readableDate \n"
        } else {
            response += "Collection is empty. \n"
        }
        return Response(response)

    }

    override fun getExpectedArguments(): List<CommandArgument> {
        return emptyList()
    }

    override fun doesRequireVehicle(): Boolean {
        return false
    }
}