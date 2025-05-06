package org.example.commands

import org.example.IO.IOManager
import org.example.core.CollectionManager
import org.example.core.Response
import org.example.model.Vehicle

class HelpCommand(private val commands: Map<String, CommandInterface>) : Command(
    name = "help",
    description = "Display a list of all available commands.",
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
        var response = "Available Commands: \n"
        commands.forEach { command ->
            response += "${command.key} - ${command.value.getDescription()} \n"
        }
        response += "execute_script <filename>: executes a script from a file. \n"
        response += "exit : Exits the program without saving. \n"
        return Response(response)

    }


}