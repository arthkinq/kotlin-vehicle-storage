package gui

import app.MainApp
import core.ApiClient
import common.CommandDescriptor // Убедись, что импортирован
import common.Request
import core.ScriptProcessor
import core.VehicleReader
import javafx.application.Platform
import javafx.fxml.FXML
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ListView // Если будешь использовать ListView
import javafx.scene.control.ScrollPane
import javafx.scene.text.Text // Для TextFlow
import javafx.stage.FileChooser
import javafx.stage.Stage
import myio.ConsoleInputManager
import myio.ConsoleOutputManager
import myio.IOManager

class MainController {

    lateinit var commandsScrollPane: ScrollPane


    @FXML
    private lateinit var currentUserLabel: Label
    @FXML
    private lateinit var connectionStatusLabel: Label


    private lateinit var apiClient: ApiClient
    private lateinit var mainApp: MainApp
    private val commandRegistry = mutableMapOf<String, CommandDescriptor>()
    private var internalLoggedInUsername: String? = null // Приватное поле для хранения имени
    private lateinit var currentStage: Stage
    private var ioManagerForApi= IOManager(
        ConsoleInputManager(),
        ConsoleOutputManager()
    )

    fun initialize() {
        //commandsTextFlow.text = "Waiting for server connection and login..."
        //logoutButton.isDisable = true
    }

    fun setApiClient(apiClient: ApiClient) {
        this.apiClient = apiClient
        setupApiClientListeners()
    }

    fun setMainApp(mainApp: MainApp) {
        this.mainApp = mainApp
    }

    fun setCurrentStage(stage: Stage) {
        this.currentStage = stage
    }

    fun setLoggedInUser(username: String) {
        this.internalLoggedInUsername = username
        refreshUIState()
    }

    private fun refreshUIState() {
        Platform.runLater {
            val userToDisplay = internalLoggedInUsername ?: apiClient.getCurrentUserCredentials()?.first

            if (userToDisplay != null) {
                currentUserLabel.text = "$userToDisplay"
                //logoutButton.isDisable = false
            } else {
                currentUserLabel.text = "Not logged in"
                //logoutButton.isDisable = true
            }

            if (apiClient.isConnected()) {
                connectionStatusLabel.text = "Подключен к серверу"
                if (commandRegistry.isEmpty() && userToDisplay != null) {
                    //commandsTextFlow.text = "Connected. Requesting command list..."
                } else if (commandRegistry.isNotEmpty() /*&& commandsTextFlow.text.contains (
                        "Disconnected",
                        ignoreCase = true
                    )*/
                ) {
                    updateCommandDisplay(commandRegistry.values.toList())
                } else if (commandRegistry.isEmpty() && userToDisplay == null) {
                    //commandsTextFlow.text = "Connected. Please login to see commands."
                }
            } else {
                connectionStatusLabel.text = "Connection: Disconnected"
                if (commandRegistry.isEmpty()) {
                   // commandsTextFlow.text = "Disconnected. Waiting for connection."
                } else {
                    //commandsTextFlow.text = "(Commands loaded, but currently disconnected)"
                }

            }
        }
    }

    private fun setupApiClientListeners() {
        apiClient.onCommandDescriptorsUpdated = { descriptors ->
            Platform.runLater {
                updateCommandDisplay(descriptors)
                refreshUIState()
            }
        }

        apiClient.onConnectionStatusChanged = { isConnected, message ->
            Platform.runLater {
                val statusMsg = message ?: if (isConnected) "Connection established." else "Disconnected."
                connectionStatusLabel.text = "Connection: $statusMsg"

                if (isConnected) {
                    val currentCredsInApiClient = apiClient.getCurrentUserCredentials()
                    if (currentCredsInApiClient != null) {

                        if (this.internalLoggedInUsername != currentCredsInApiClient.first) {
                            this.internalLoggedInUsername = currentCredsInApiClient.first
                        }
                        ioManagerForApi.outputLine("Network: Re-established connection as ${currentCredsInApiClient.first}.")
                    } else {
                        // Подключились, но в ApiClient нет кредов (например, релогин не был или не удался)
                        this.internalLoggedInUsername = null // Убедимся, что локальное состояние соответствует
                        ioManagerForApi.outputLine("Network: Connection established. Please login if needed.")
                    }
                    if (commandRegistry.isEmpty() && internalLoggedInUsername != null) {
                        //commandsTextFlow.text = "Connected. Waiting for command list..."
                    }
                } else { // Disconnected
                    ioManagerForApi.outputLine("Network: Connection lost. Attempts to execute commands will try to reconnect.")
                    // Не сбрасываем internalLoggedInUsername здесь, чтобы UI не "моргал"
                    // до явного logout или неудачного релогина.
                }
                refreshUIState() // Обновляем весь UI
            }
        }
    }

    @FXML
    private fun handleLogout() {
        apiClient.clearCurrentUserCredentials()
        this.internalLoggedInUsername = null // Сбрасываем локальное имя
        commandRegistry.clear()
        //commandsTextFlow.text = "Logged out. Please login to see commands."
        refreshUIState() // Обновит UI (кнопка станет неактивной и т.д.)
        mainApp.onLogout(currentStage)
    }

    private fun updateCommandDisplay(descriptors: List<CommandDescriptor>) {
        commandRegistry.clear()
        descriptors.forEach { commandRegistry[it.name.lowercase()] = it }

        if (descriptors.isEmpty()) {
            //commandsTextFlow.text = "No commands available from server."
            // commandListView.items.setAll("No commands available from server.")
            return
        }

        val sb = StringBuilder()
        descriptors.sortedBy { it.name }.forEach { desc ->
            sb.append(desc.name)
            if (desc.arguments.isNotEmpty() && desc.arguments.none { it.type == common.ArgumentType.NO_ARGS }) {
                desc.arguments.forEach { arg ->
                    sb.append(if (arg.isOptional) " [${arg.name}]" else " <${arg.name}>")
                }
            }
            sb.append(" - ${desc.description}\n")
            // Можно добавить детали аргументов, если нужно, как в HelpCommand
        }
        //commandsTextFlow.text = sb.toString()

        // Для ListView:
        // val commandNames = descriptors.map { "${it.name} - ${it.description}" }.sorted()
        // commandListView.items.setAll(commandNames)
    }


}