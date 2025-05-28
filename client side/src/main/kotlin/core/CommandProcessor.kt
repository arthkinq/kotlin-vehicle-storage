package core

import myio.IOManager
import myio.InputManager
import common.ArgumentType
import common.CommandDescriptor
import common.Request
import model.Vehicle
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

class CommandProcessor(
    private val apiClient: ApiClient,
    private val ioManager: IOManager
) {
    private val vehicleReader: VehicleReader = VehicleReader(ioManager)
    private val maxRecursionDepth = 5
    private var recursionDepth = 0
    private val executedScripts = mutableSetOf<String>()

    private var commandRegistry = mutableMapOf<String, CommandDescriptor>()
    private var oldCommandRegistry = mutableMapOf<String, CommandDescriptor>()

    @Volatile
    private var commandListInitialized = false
    @Volatile
    private var currentlyConnected = false
    private var currentUsername: String? = null
    private var currentPassword: String? = null

    init {
        apiClient.onCommandDescriptorsUpdated = { newDescriptors ->
            updateCommandRegistry(newDescriptors)
            val message: String
            if (newDescriptors.isNotEmpty()) {
                if (!commandListInitialized) {
                    commandListInitialized = true
                    message = "Initial command list received (${commandRegistry.size} commands). Type 'help' for details."
                } else if (commandRegistry != oldCommandRegistry) {
                    message = "Client command list updated from server (${commandRegistry.size} commands)."
                } else {
                    message = ""
                }
            } else {
                message = if (commandListInitialized) {
                    "Warning: Received an empty command list from server after initial setup."
                } else {
                    "Warning: Connected to server, but no commands are currently available."
                }
                if (commandListInitialized && newDescriptors.isEmpty()) commandListInitialized = false
            }
            if (message.isNotBlank()) ioManager.outputLine(message)
        }

        apiClient.onConnectionStatusChanged = { isConnected, msg ->
            currentlyConnected = isConnected
            ioManager.outputLine("Network: ${msg ?: if (isConnected) "Connection established." else "Disconnected."}")
            if (!isConnected) {
                ioManager.outputLine("Command list might be outdated due to disconnection. Credentials cleared.")
                currentUsername = null
                currentPassword = null
            }
        }
    }

    private fun updateCommandRegistry(newDescriptors: List<CommandDescriptor>) {
        val newRegistry = mutableMapOf<String, CommandDescriptor>()
        newDescriptors.forEach { descriptor ->
            newRegistry[descriptor.name.lowercase()] = descriptor
        }
        oldCommandRegistry = commandRegistry
        commandRegistry = newRegistry
    }

    fun start() {
        ioManager.outputLine("Transport Manager Client. Type 'exit' to quit.")
        ioManager.outputLine("Available local commands: register, login, exit, execute_script")
        ioManager.outputLine("Other commands are fetched from the server after connection.")


        var continueExecution = true
        while (continueExecution) {
            val promptUser = currentUsername ?: "Guest"
            ioManager.outputInline("$promptUser > ")
            val userInput = ioManager.readLine().trim()

            val parts = userInput.split("\\s+".toRegex(), 2)
            val commandNameInput = parts.getOrNull(0)?.lowercase() ?: ""
            val argumentString = parts.getOrNull(1) ?: ""

            when (commandNameInput) {
                "exit" -> {
                    ioManager.outputLine("Exiting client application...")
                    continueExecution = false
                }
                "register" -> handleAuthCommand("register", argumentString)
                "login" -> handleAuthCommand("login", argumentString)
                "execute_script" -> {
                    if (argumentString.isNotBlank()) {
                        executeScript(argumentString)
                    } else {
                        ioManager.outputLine("Usage: execute_script <filename>")
                    }
                }
                else -> {
                    if (commandNameInput.isNotBlank()) {
                        if (!commandListInitialized && commandRegistry.isEmpty() && currentUsername == null) {
                            val statusMessage = if (!currentlyConnected) {
                                "Still attempting to connect. Please login or register first."
                            } else {
                                "Connected, but command list not yet received. Please login/register or wait."
                            }
                            ioManager.outputLine("$statusMessage Your command '$commandNameInput' might not be recognized yet.")
                        }
                        processSingleCommand(commandNameInput, argumentString)
                    }
                }
            }
        }
        ioManager.outputLine("Client command loop finished.")
        apiClient.close()
    }

    private fun handleAuthCommand(commandName: String, argumentString: String) {
        val args = argumentString.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        if (args.size != 2) {
            ioManager.error("Usage: $commandName <username> <password>")
            return
        }
        val tempUsername = args[0]
        val tempPassword = args[1]

        val request = Request(
            body = listOf(commandName, tempUsername, tempPassword),
            username = tempUsername,
            password = tempPassword
        )

        val response = apiClient.sendRequestAndWaitForResponse(request)
        if (response != null) {
            ioManager.outputLine(response.responseText)
            if (commandName == "login" && !response.responseText.startsWith("Error:")) {
                currentUsername = tempUsername
                currentPassword = tempPassword
                ioManager.outputLine("Credentials stored for this session.")
            } else if (commandName == "login" && response.responseText.startsWith("Error:")) {
                currentUsername = null
                currentPassword = null
            }
        } else {
            ioManager.error("No response from server for $commandName command or request timed out.")
        }
    }


    private fun processSingleCommand(commandNameInLowercase: String, argumentString: String) {
        if (currentUsername == null || currentPassword == null) {
            if (commandNameInLowercase != "help") {
                ioManager.error("Error: You must be logged in to execute commands. Use 'login <username> <password>'.")
                return
            } else {
                ioManager.outputLine("Available local commands: register, login, exit, execute_script")
                ioManager.outputLine("Other commands are fetched from the server after connection.")
                return
            }
        }

        val descriptor = commandRegistry[commandNameInLowercase]
        if (descriptor == null) {
            val baseMsg = "Command '$commandNameInLowercase' is not recognized by the client."
            val suggestion = if (commandListInitialized || commandRegistry.isNotEmpty()) "Type 'help' for available commands."
            else "Command list not yet received from server, or command is invalid."
            ioManager.outputLine("$baseMsg $suggestion")
            return
        }

        var vehicleForRequest: Vehicle? = null
        val providedArgs = if (argumentString.isNotBlank()) {
            argumentString.split("\\s+".toRegex())
        } else {
            emptyList()
        }

        val minRequiredArgs = descriptor.arguments.count { !it.isOptional && it.type != ArgumentType.NO_ARGS }
        val maxTotalArgs = if (descriptor.arguments.any { it.type == ArgumentType.NO_ARGS }) 0 else descriptor.arguments.size

        if (providedArgs.size < minRequiredArgs || (maxTotalArgs > 0 && providedArgs.size > maxTotalArgs)) {
            ioManager.error("Error: Invalid number of arguments for command '${descriptor.name}'.")
            printCommandUsage(descriptor)
            return
        }

        val finalArgsForServer = providedArgs.toMutableList()

        if (descriptor.requiresVehicleObject) {
            ioManager.outputLine("Command '${descriptor.name}' requires vehicle data.")
            ioManager.outputLine("Please enter data for the vehicle:")
            vehicleForRequest = vehicleReader.readVehicle()

            if (descriptor.arguments.all { it.isOptional || it.type == ArgumentType.NO_ARGS }) {
                finalArgsForServer.clear()
            }
        }

        val request = Request(
            body = listOf(descriptor.name) + finalArgsForServer,
            vehicle = vehicleForRequest,
            username = currentUsername,
            password = currentPassword
        )

        val response = apiClient.sendRequestAndWaitForResponse(request)
        if (response != null) {
            ioManager.outputLine(response.responseText)
            if (response.responseText.contains("Authentication failed", ignoreCase = true) ||
                response.responseText.contains("token expired", ignoreCase = true) ||
                response.responseText.contains("session invalid", ignoreCase = true)) {
                ioManager.outputLine("Authentication error from server. Clearing local credentials.")
                currentUsername = null
                currentPassword = null
            }
        }
    }

    private fun printCommandUsage(descriptor: CommandDescriptor?) {
        if (descriptor == null) return
        val usage = StringBuilder("Usage: ${descriptor.name}")
        descriptor.arguments.forEach { arg ->
            if (arg.type != ArgumentType.NO_ARGS) {
                usage.append(if (arg.isOptional) " [${arg.name}]" else " <${arg.name}>")
            }
        }
        ioManager.outputLine(usage.toString())
        if (descriptor.description.isNotBlank()) {
            ioManager.outputLine("  ${descriptor.description}")
        }
        descriptor.arguments.filter { it.type != ArgumentType.NO_ARGS && !it.description.isNullOrBlank() }
            .forEach { arg ->
                ioManager.outputLine("    ${arg.name} (${arg.type}): ${arg.description}")
            }
        if (descriptor.requiresVehicleObject) {
            ioManager.outputLine("  (This command also requires providing vehicle data interactively)")
        }
    }

    private fun executeScript(fileName: String) {
        if (recursionDepth >= maxRecursionDepth) {
            ioManager.error("Max script recursion depth ($maxRecursionDepth) exceeded. Aborting script '$fileName'.")
            return
        }

        val filePathString = try { Paths.get(fileName).toAbsolutePath().toString() }
        catch (e: InvalidPathException) {
            ioManager.error("Invalid script file path '$fileName': ${e.message}")
            return
        }
        if (filePathString in executedScripts) {
            ioManager.error("Recursion detected: Script '$fileName' (resolved to '$filePathString') is already running in this call chain. Aborting.")
            return
        }

        val path: Path
        try {
            path = Paths.get(fileName)
            if (!Files.exists(path)) {
                ioManager.error("Script file not found: $fileName (Resolved to: ${path.toAbsolutePath()})")
                return
            }
            if (!Files.isReadable(path)) {
                ioManager.error("Cannot read script file (access denied): $fileName (Resolved to: ${path.toAbsolutePath()})")
                return
            }
        } catch (e: InvalidPathException) {
            ioManager.error("Invalid script file path '$fileName': ${e.message}")
            return
        } catch (e: SecurityException) {
            ioManager.error("Security error accessing script file '$fileName': ${e.message}")
            return
        } catch (e: IOException) {
            ioManager.error("IO error accessing script file '$fileName': ${e.message}")
            return
        } catch (e: Exception) {
            ioManager.error("Unexpected error accessing script file '$fileName': ${e.message}")
            return
        }

        recursionDepth++
        executedScripts.add(filePathString)
        ioManager.outputLine("Executing script: $fileName (Depth: $recursionDepth)")
        val originalInputManager = ioManager.getInput()
        try {
            Files.newBufferedReader(path).use { reader ->
                val scriptInputManager = object : InputManager {
                    override fun readLine(): String? = reader.readLine()
                    override fun hasInput(): Boolean = reader.ready()
                }
                ioManager.setInput(scriptInputManager)
                var lineNumber = 0
                while (true) {
                    lineNumber++
                    val line = scriptInputManager.readLine()?.trim() ?: break
                    if (line.isEmpty() || line.startsWith("#")) continue
                    ioManager.outputLine("[Script Line $lineNumber]> $line")

                    val parts = line.split("\\s+".toRegex(), 2)
                    val commandNameFromScript = parts.getOrNull(0)?.lowercase() ?: ""
                    val argumentStringFromScript = parts.getOrNull(1) ?: ""

                    when (commandNameFromScript) {
                        "register" -> handleAuthCommand("register", argumentStringFromScript)
                        "login" -> handleAuthCommand("login", argumentStringFromScript)
                        "execute_script" -> {
                            if (argumentStringFromScript.isNotBlank()) executeScript(argumentStringFromScript)
                            else ioManager.error("[Script Error Line $lineNumber] Usage: execute_script <filename>")
                        }
                        else -> {
                            val descriptor = commandRegistry[commandNameFromScript]
                            if (descriptor != null) {
                                if (descriptor.requiresVehicleObject) {
                                    val scriptArgs = if (argumentStringFromScript.isNotBlank()) argumentStringFromScript.split("\\s+".toRegex()) else emptyList()
                                    processVehicleCommandInScript(descriptor, scriptArgs, scriptInputManager, lineNumber)
                                } else {
                                    processSingleCommand(commandNameFromScript, argumentStringFromScript)
                                }
                            } else if (commandNameFromScript.isNotBlank()) {
                                ioManager.error("[Script Error Line $lineNumber] Unknown command: '$commandNameFromScript'")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            ioManager.error("Error during script execution '$fileName': ${e.message}")
        } finally {
            ioManager.setInput(originalInputManager)
            executedScripts.remove(filePathString)
            recursionDepth--
            ioManager.outputLine("Finished executing script: $fileName (Depth: $recursionDepth)")
        }
    }

    private fun processVehicleCommandInScript(
        descriptor: CommandDescriptor,
        argsFromScriptLine: List<String>,
        scriptInputManager: InputManager,
        baseLineNumber: Int
    ) {
        if (currentUsername == null || currentPassword == null) {
            ioManager.error("[Script Error Line $baseLineNumber] Cannot execute command '${descriptor.name}' from script: Not logged in.")
            for (i in 1..7) scriptInputManager.readLine()
            return
        }

        val vehicleData = mutableListOf<String>()
        ioManager.outputLine("[Script Processing '${descriptor.name}'] Expecting 7 lines of vehicle data...")
        for (i in 1..7) {
            val dataLine = scriptInputManager.readLine()
            if (dataLine == null) {
                ioManager.error("[Script Error Line ${baseLineNumber + i}] Unexpected end of script. Expected data for '${descriptor.name}'.")
                return
            }
            vehicleData.add(dataLine.trim())
        }

        try {
            val vehicleFromScript = vehicleReader.readVehicleFromScript(vehicleData)
            val finalBodyForScript = listOf(descriptor.name) + argsFromScriptLine
            val request = Request(
                body = finalBodyForScript,
                vehicle = vehicleFromScript,
                username = currentUsername,
                password = currentPassword
            )
            ioManager.outputLine("Sending command '${descriptor.name}' from script to server...")
            val response = apiClient.sendRequestAndWaitForResponse(request)
            if (response != null) {
                ioManager.outputLine("[Script INFO] '${descriptor.name}' command response: ${response.responseText}")
                if (response.responseText.contains("Authentication failed", ignoreCase = true)) {
                    ioManager.outputLine("Authentication error from server during script. Clearing local credentials.")
                    currentUsername = null
                    currentPassword = null
                }
            } else {
                ioManager.error("[Script ERROR] No response for '${descriptor.name}' command from script or request timed out.")
            }
        } catch (e: Exception) {
            ioManager.error("[Script ERROR] Error processing '${descriptor.name}' command from script: ${e.message}. Data was: $vehicleData")
        }
    }
}

