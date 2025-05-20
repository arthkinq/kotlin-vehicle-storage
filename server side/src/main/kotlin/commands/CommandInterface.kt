package commands

import myio.IOManager
import core.CollectionManager
import common.CommandArgument
import common.Response
import model.Vehicle

interface CommandInterface {
    fun getName(): String
    fun getDescription(): String
    fun execute(
        args: List<String> = emptyList(),
        collectionManager: CollectionManager,
        ioManager: IOManager,
        vehicle: Vehicle? = null,
    ): Response

    fun getExpectedArguments(): List<CommandArgument>
    fun doesRequireVehicle(): Boolean
}