package org.example.commands


import org.example.IO.IOManager
import org.example.core.CollectionManager
import org.example.core.Response
import org.example.model.Vehicle
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
        collectionManager: CollectionManager,
        ioManager: IOManager,
        vehicle: Vehicle?
    ): Response {
        if (!checkSizeOfArgs(args.size)) {
            return Response("Error: Args can be size ${size}.")
        }
        var response =
            "Info: \n Collection type: ${collectionManager.getAll()::class.simpleName} \n Amount of elements: ${collectionManager.size()} \n"

        if (collectionManager.getAll().isNotEmpty()) {
            val readableDate = Instant.ofEpochMilli(collectionManager.getAll().first().creationDate)
                .atZone(ZoneId.of("UTC"))
                .toLocalDateTime()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            response += "Creation date: $readableDate \n"
        } else {
            response += "Collection is empty. \n"
        }
        return Response(response)

    }
}