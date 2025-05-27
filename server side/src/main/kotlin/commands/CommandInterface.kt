package commands

import myio.IOManager
import core.CollectionManager
import common.CommandArgument
import common.Response
import core.VehicleService
import model.Vehicle

interface CommandInterface {
    fun getName(): String
    fun getDescription(): String
    fun execute(
        args: List<String> = emptyList(),
        vehicleService: VehicleService,
        ioManager: IOManager,
        vehicle: Vehicle? = null,
        userId: Int? = null
    ): Response

    fun getExpectedArguments(): List<CommandArgument>
    fun doesRequireVehicle(): Boolean
}