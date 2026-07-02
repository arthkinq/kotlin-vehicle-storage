package core

import common.CommandDescriptor
import common.Request
import model.Vehicle
import myio.IOManager
import myio.InputManager
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

class ScriptProcessor(
    private val apiClient: ApiClient,
    private val ioManager: IOManager,
    private val vehicleReader: VehicleReader,
    private val getCommandRegistry: () -> Map<String, CommandDescriptor>,
    private val onScriptOutput: (String) -> Unit,
    private val onScriptError: (String) -> Unit,
    private val onAuthErrorDuringScript: () -> Unit
) {
    private val maxRecursionDepth = 5
    private var currentRecursionDepth = 0
    private val executedScriptPaths = mutableSetOf<String>()

    fun executeScript(filePath: String) {
        if (currentRecursionDepth >= maxRecursionDepth) {
            onScriptError("Max script recursion depth ($maxRecursionDepth) exceeded for '$filePath'.")
            return
        }

        val absolutePath = try {
            Paths.get(filePath).toAbsolutePath().toString()
        } catch (e: InvalidPathException) {
            onScriptError("Invalid script file path '$filePath': ${e.message}")
            return
        }

        if (absolutePath in executedScriptPaths) {
            onScriptError("Recursion detected: Script '$filePath' (resolved to '$absolutePath') is already running in this call chain.")
            return
        }

        val path: Path = Paths.get(absolutePath)
        try {
            if (!Files.exists(path)) {
                onScriptError("Script file not found: $absolutePath")
                return
            }
            if (!Files.isReadable(path)) {
                onScriptError("Cannot read script file (access denied): $absolutePath")
                return
            }
        } catch (e: SecurityException) {
            onScriptError("Security error accessing script file '$absolutePath': ${e.message}")
            return
        } catch (e: IOException) {
            onScriptError("IO error accessing script file '$absolutePath': ${e.message}")
            return
        } catch (e: Exception) {
            onScriptError("Unexpected error accessing script file '$absolutePath': ${e.message}")
            return
        }

        currentRecursionDepth++
        executedScriptPaths.add(absolutePath)
        onScriptOutput("Executing script: $filePath (Depth: $currentRecursionDepth)")

        try {
            Files.newBufferedReader(path).use { reader ->
                val scriptInputManager = object : InputManager {
                    override fun readLine(): String? = reader.readLine()
                    override fun hasInput(): Boolean = reader.ready()
                }

                var lineNumber = 0
                while (true) {
                    lineNumber++
                    val line = scriptInputManager.readLine()?.trim() ?: break
                    if (line.isEmpty() || line.startsWith("#")) continue

                    onScriptOutput("[Script Line $lineNumber]> $line")
                    val parts = line.split("\\s+".toRegex(), 2)
                    val commandNameFromScript = parts.getOrNull(0)?.lowercase() ?: ""
                    val argumentStringFromScript = parts.getOrNull(1) ?: ""

                    if (commandNameFromScript.isBlank()) continue

                    if (!apiClient.isConnected()) {
                        onScriptOutput("Script '$filePath': Not connected. Attempting to connect for command '$commandNameFromScript'...")
                        if (!apiClient.connectIfNeeded()) {
                            onScriptError("Script '$filePath': Failed to connect to server. Command '$commandNameFromScript' skipped.")
                            continue
                        }

                        try { Thread.sleep(200) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
                    }

                    val commandRegistry = getCommandRegistry()
                    val descriptor = commandRegistry[commandNameFromScript]

                    if (commandNameFromScript == "execute_script") {
                        if (argumentStringFromScript.isNotBlank()) {
                            executeScript(argumentStringFromScript)
                        } else {
                            onScriptError("[Script Error Line $lineNumber] Usage: execute_script <filename>")
                        }
                    } else if (descriptor != null) {
                        val scriptArgs = if (argumentStringFromScript.isNotBlank()) argumentStringFromScript.split("\\s+".toRegex()) else emptyList()
                        processScriptCommand(descriptor, scriptArgs, scriptInputManager, lineNumber)
                    } else {
                        onScriptError("[Script Error Line $lineNumber] Unknown command: '$commandNameFromScript'")
                    }
                }
            }
        } catch (e: Exception) {
            onScriptError("Exception during script execution '$filePath': ${e.message}")
            e.printStackTrace()
        } finally {
            executedScriptPaths.remove(absolutePath)
            currentRecursionDepth--
            onScriptOutput("Finished executing script: $filePath (Depth: $currentRecursionDepth)")
        }
    }

    private fun processScriptCommand(
        descriptor: CommandDescriptor,
        argsFromScriptLine: List<String>,
        scriptInputManager: InputManager,
        baseLineNumber: Int
    ) {
        val currentUserCreds = apiClient.getCurrentUserCredentials()
        if (currentUserCreds == null && descriptor.name != "login" && descriptor.name != "register") {
            onScriptError("[Script Error Line $baseLineNumber] Cannot execute command '${descriptor.name}': Not logged in.")

            if (descriptor.requiresVehicleObject) {
                for (i in 1..7) scriptInputManager.readLine()
            }
            return
        }

        var vehicleForRequest: Vehicle? = null
        if (descriptor.requiresVehicleObject) {
            val vehicleDataLines = mutableListOf<String>()
            onScriptOutput("[Script Processing '${descriptor.name}'] Expecting 7 lines of vehicle data...")
            for (i in 1..7) {
                val dataLine = scriptInputManager.readLine()
                if (dataLine == null) {
                    onScriptError("[Script Error Line ${baseLineNumber + i}] Unexpected end of script. Expected data for '${descriptor.name}'.")
                    return
                }
                vehicleDataLines.add(dataLine.trim())
            }
            try {
                vehicleForRequest = vehicleReader.readVehicleFromScript(vehicleDataLines)
            } catch (e: Exception) {
                onScriptError("[Script Error Line $baseLineNumber] Error parsing vehicle data for '${descriptor.name}': ${e.message}")
                return
            }
        }

        val finalBody = listOf(descriptor.name) + argsFromScriptLine

        val request = Request(
            body = finalBody,
            vehicle = vehicleForRequest,
            username = currentUserCreds?.first,
            password = currentUserCreds?.second
        )

        onScriptOutput("Sending command '${descriptor.name}' from script...")
        val response = apiClient.sendRequestAndWaitForResponse(request)

        if (response != null) {
            onScriptOutput("[Script INFO] '${descriptor.name}' response: ${response.responseText}")
            if (response.responseText.contains("Authentication failed", ignoreCase = true) ||
                response.responseText.contains("session invalid", ignoreCase = true)
            ) {
                onScriptError("Authentication error from server during script for command '${descriptor.name}'.")
                apiClient.clearCurrentUserCredentials()
                onAuthErrorDuringScript()
            }

        } else {
            onScriptError("[Script ERROR] No response for '${descriptor.name}' command or request timed out.")
        }
    }
}