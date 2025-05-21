package commands

import myio.IOManager
import common.ArgumentType
import common.CommandArgument
import common.Response
import core.*
import model.Vehicle

class HelpCommand(private val commandProcessor: CommandProcessor) : Command(
    name = "help",
    description = "Display a list of all available commands and their usage.",
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

        val descriptors = commandProcessor.getCommandDescriptors()
        if (descriptors.isEmpty()) {
            return Response("No commands available on the server.")
        }

        val responseText = StringBuilder("Available Commands:\n")
        descriptors.forEach { desc ->
            responseText.append("  ${desc.name}")
            if (desc.arguments.isNotEmpty() && desc.arguments.none { it.type == ArgumentType.NO_ARGS }) {
                desc.arguments.forEach { arg ->
                    responseText.append(if (arg.isOptional) " [${arg.name}]" else " <${arg.name}>")
                }
            }
            responseText.append(" - ${desc.description}\n")
            desc.arguments.forEach { arg ->
                if (arg.type != ArgumentType.NO_ARGS && !arg.description.isNullOrBlank()) {
                    responseText.append("      ${arg.name}: ${arg.description}\n")
                }
            }
            if (desc.requiresVehicleObject) {
                responseText.append("      (this command requires providing vehicle data interactively)\n")
            }
        }
        responseText.append("  execute_script <filename> - executes a script from a file.\n")
        responseText.append("  exit - Exits the client program.\n")

        return Response(responseText.toString())
    }

    override fun getExpectedArguments(): List<CommandArgument> = emptyList()
    override fun doesRequireVehicle(): Boolean = false
}