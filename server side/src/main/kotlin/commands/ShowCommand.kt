package commands

import myio.IOManager
import core.CollectionManager
import common.CommandArgument
import common.Response
import model.Vehicle // Импорт нужен, если Vehicle используется в сигнатуре execute

class ShowCommand : Command(
    name = "show",
    description = "Display all the items in the collection.",
    size = 0
) {
    override fun execute(
        args: List<String>,
        collectionManager: CollectionManager,
        ioManager: IOManager,
        vehicle: Vehicle?
    ): Response {
        if (!checkSizeOfArgs(args.size)) {
            return Response("Error: '${getName()}' command takes no arguments.")
        }

        if (collectionManager.isEmpty()) {
            return Response("Collection is empty.")
        } else {
            val stringBuilder = StringBuilder("Vehicles in collection (sorted by name):\n")
            collectionManager.getAll()
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