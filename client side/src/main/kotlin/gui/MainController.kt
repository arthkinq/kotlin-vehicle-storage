package gui

import app.MainApp
import common.CommandDescriptor
import common.Request
import core.ApiClient
import javafx.application.Platform
import javafx.fxml.FXML
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.Alert
import javafx.scene.control.TextInputDialog // Для примера сбора аргументов
import javafx.scene.control.Tooltip
import javafx.scene.layout.VBox
import javafx.scene.text.Font
import javafx.stage.FileChooser // Для будущего execute_script
import javafx.stage.Stage
import model.Vehicle // Для handleCommandExecution

class MainController {

    @FXML private lateinit var commandsVBox: VBox
    @FXML private lateinit var currentUserLabel: Label
    @FXML private lateinit var connectionStatusLabel: Label
    @FXML private lateinit var logoutButton: Button

    private lateinit var apiClient: ApiClient
    private lateinit var mainApp: MainApp
    private lateinit var currentStage: Stage

    private val commandRegistry = mutableMapOf<String, CommandDescriptor>()

    fun initialize() {
        println("MainController: initialize() called.")
        commandsVBox.children.clear()
        logoutButton.isDisable = true
        // Не вызываем refreshUIState() здесь, так как apiClient еще не установлен.
        // Он будет вызван в конце setApiClient().
    }

    fun setApiClient(apiClient: ApiClient) {
        println("MainController: setApiClient() called.")
        this.apiClient = apiClient
        setupApiClientListeners() // Подписываемся на БУДУЩИЕ обновления

        // Пытаемся получить УЖЕ ЗАГРУЖЕННЫЕ команды из ApiClient
        val cachedDescriptors = apiClient.getCachedCommandDescriptors()
        if (cachedDescriptors != null) {
            println("MainController: Got ${cachedDescriptors.size} cached descriptors from ApiClient.")
            commandRegistry.clear()
            cachedDescriptors.forEach { commandRegistry[it.name.lowercase()] = it }
            // updateCommandDisplayItself() // refreshUIState ниже это сделает
        }
        refreshUIState() // Обновляем UI на основе текущего состояния (включая, возможно, кэшированные команды)
    }

    fun setMainApp(mainApp: MainApp) { this.mainApp = mainApp }
    fun setCurrentStage(stage: Stage) { this.currentStage = stage }

    // Вызывается из MainApp.showMainWindow()
    fun userLoggedIn() { // Убрали параметр username, берем из ApiClient
        println("MainController: userLoggedIn() signal received.")
        // К этому моменту ApiClient уже должен иметь установленные креды из LoginController.
        // Обновляем UI. Команды либо уже пришли (если login ответ их содержал и onCommandDescriptorsUpdated сработал),
        // либо ApiClient их запросил при подключении и они скоро придут.
        refreshUIState()
    }

    private fun refreshUIState() {
        // Убедимся, что apiClient инициализирован перед использованием
        if (!::apiClient.isInitialized) {
            println("MainController: refreshUIState - apiClient not initialized yet.")
            return
        }

        Platform.runLater {
            println("MainController: Refreshing UI State. ApiClient connected: ${apiClient.isConnected()}, User: ${apiClient.getCurrentUserCredentials()?.first}")
            val creds = apiClient.getCurrentUserCredentials()

            if (creds != null) {
                currentUserLabel.text = "User: ${creds.first}"
                logoutButton.isDisable = false
            } else {
                currentUserLabel.text = "User: Not logged in"
                logoutButton.isDisable = true
            }

            connectionStatusLabel.text = if (apiClient.isConnected()) "Connection: Connected" else "Connection: Disconnected"
            updateCommandDisplayItself() // Обновляем кнопки команд
        }
    }

    private fun setupApiClientListeners() {
        apiClient.onCommandDescriptorsUpdated = { descriptorsFromServer ->
            Platform.runLater {
                println("MainController: Listener onCommandDescriptorsUpdated received ${descriptorsFromServer.size} descriptors.")
                commandRegistry.clear()
                descriptorsFromServer.forEach { commandRegistry[it.name.lowercase()] = it }
                println("MainController: commandRegistry updated by listener. New size: ${commandRegistry.size}")
                updateCommandDisplayItself() // Перерисовываем кнопки с новым реестром
                // Не вызываем refreshUIState() здесь, чтобы избежать возможной рекурсии,
                // если refreshUIState тоже как-то влияет на запрос команд.
                // updateCommandDisplayItself должен быть достаточен для обновления кнопок.
                // Статус пользователя и соединения обновляются в onConnectionStatusChanged -> refreshUIState.
            }
        }

        apiClient.onConnectionStatusChanged = { isConnected, message ->
            Platform.runLater {
                val statusMsg = message ?: if (isConnected) "Connection established." else "Disconnected."
                println("MainController: Listener onConnectionStatusChanged. Connected: $isConnected, Message: $statusMsg")

                if (isConnected) {
                    val currentCredsInApiClient = apiClient.getCurrentUserCredentials()
                    if (currentCredsInApiClient != null) {
                        println("MainController: Connection (re-)established for ${currentCredsInApiClient.first}.")
                        // Если это реконнект и команды были, но пропали (например, из-за очистки commandRegistry при дисконнекте),
                        // ApiClient при completeConnectionSetup должен был запросить команды.
                        // refreshUIState() ниже их отобразит, когда они придут.
                        if (commandRegistry.isEmpty()) {
                            println("MainController: Command registry is empty, waiting for update from server.")
                        }
                    } else {
                        println("MainController: Connection established. User not logged in.")
                    }
                } else { // Disconnected
                    println("MainController: Connection lost.")
                    // Не сбрасываем креды в ApiClient. Кнопка Logout останется активной, если юзер был залогинен.
                    // Список команд не очищаем здесь, updateCommandDisplayItself покажет плейсхолдер "Not connected".
                }
                refreshUIState() // Обновляем весь UI
            }
        }
    }

    private fun updateCommandDisplayItself() {
        println("MainController: updateCommandDisplayItself. commandRegistry size: ${commandRegistry.size}, User: ${apiClient.getCurrentUserCredentials()?.first}")
        commandsVBox.children.clear()

        val displayableDescriptors = mutableListOf<CommandDescriptor>()
        // Добавляем команды из реестра, только если пользователь залогинен
        // (предполагаем, что все серверные команды требуют логина, кроме, возможно, help)
        val currentUser = apiClient.getCurrentUserCredentials()
        if (currentUser != null) {
            displayableDescriptors.addAll(this.commandRegistry.values)
            // Добавляем execute_script, если пользователь залогинен и такой команды нет от сервера
            if (this.commandRegistry.values.none { it.name.equals("execute_script", ignoreCase = true) }) {
                displayableDescriptors.add(
                    CommandDescriptor(
                        name = "execute_script",
                        description = "Execute commands from a script file.",
                        arguments = listOf(common.CommandArgument("filename", common.ArgumentType.STRING, false, "Path to the script file")),
                        requiresVehicleObject = false
                    )
                )
            }
        }

        if (displayableDescriptors.isEmpty()) {
            val placeholderText = when {
                !apiClient.isConnected() -> "Not connected. Commands unavailable."
                currentUser == null -> "Connected. Please login to see commands."
                else -> "Connected. Loading commands or no commands available..." // Залогинен, но команд нет
            }
            commandsVBox.children.add(Label(placeholderText).apply { font = Font.font("Tahoma", 15.0) })
            println("MainController: No displayable command items. Placeholder: '$placeholderText'")
            return
        }

        println("MainController: Creating ${displayableDescriptors.size} command buttons.")
        displayableDescriptors.sortedBy { it.name }.forEach { desc ->
            val button = Button(desc.name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() })
            button.maxWidth = Double.MAX_VALUE
            button.prefHeight = 40.0
            button.isWrapText = true
            button.font = Font.font("Tahoma", 14.0)
            button.tooltip = Tooltip(desc.description)
            button.setOnAction { handleCommandExecution(desc) }
            commandsVBox.children.add(button)
        }
    }

    @FXML
    private fun handleLogout() {
        println("Logout button clicked by ${apiClient.getCurrentUserCredentials()?.first ?: "Guest"}")
        apiClient.clearCurrentUserCredentials()
        this.commandRegistry.clear()
        refreshUIState()
        mainApp.onLogout(currentStage)
    }

    private fun handleCommandExecution(descriptor: CommandDescriptor) {
        println("UI: Command button clicked: ${descriptor.name}")

        val currentCreds = apiClient.getCurrentUserCredentials()
        if (currentCreds == null) { // Все серверные команды требуют логина
            showErrorAlert("Authentication Error", "You must be logged in to execute this command.")
            return
        }

        // Preflight check для соединения
        if (!apiClient.isConnected()) {
            showInfoAlert("Connection", "Not connected. Attempting to connect for command '${descriptor.name}'...")
            Thread {
                val connected = apiClient.connectIfNeeded()
                Platform.runLater {
                    if (connected) {
                        showInfoAlert("Connection", "Reconnected. Please try your command '${descriptor.name}' again.")
                        // После реконнекта ApiClient должен был запросить команды,
                        // onCommandDescriptorsUpdated обновит UI.
                    } else {
                        showErrorAlert("Connection Error", "Failed to connect to server. Command '${descriptor.name}' not sent.")
                    }
                }
            }.start()
            return // Пользователь должен будет нажать кнопку команды еще раз
        }

        // Логика сбора аргументов и Vehicle (TODO: Заменить на GUI диалоги)
        val argsList = mutableListOf<String>()
        var vehicleForRequest: Vehicle? = null
        var proceedWithExecution = true

        if (descriptor.arguments.any { it.type != common.ArgumentType.NO_ARGS && !it.isOptional }) {
            showInfoAlert("Input Required", "Command '${descriptor.name}' requires arguments. (GUI for this is a TODO)")
            println("TODO: Implement GUI for argument input for command ${descriptor.name}")
            return // Заглушка: не выполняем команду, если нужны аргументы и нет GUI для их ввода
        }

        if (descriptor.requiresVehicleObject) {
            showInfoAlert("Vehicle Input", "Command '${descriptor.name}' requires Vehicle data. (GUI for this is a TODO)")
            println("TODO: Implement GUI for Vehicle input for command ${descriptor.name}")
            return // Заглушка: не выполняем команду, если нужен Vehicle и нет GUI для его ввода
        }

        val request = Request(
            body = listOf(descriptor.name) + argsList, // Используем "чистое" имя из дескриптора
            vehicle = vehicleForRequest,
            username = currentCreds.first,
            password = currentCreds.second
        )

        showInfoAlert("Processing", "Sending command '${descriptor.name}' to server...")
        Thread {
            val response = apiClient.sendRequestAndWaitForResponse(request)
            Platform.runLater {
                if (response != null) {
                    showInfoAlert("Server Response - ${descriptor.name}", response.responseText)
                    if (response.responseText.contains("Authentication failed", ignoreCase = true)) {
                        apiClient.clearCurrentUserCredentials()
                        refreshUIState() // Обновит UI, покажет "Not logged in"
                        mainApp.onLogout(currentStage) // Сессия невалидна, возвращаем к логину
                    }
                    // TODO: Обновить TableView/Visualization, если команда изменяла данные
                } else {
                    showErrorAlert("Server Error", "No response or timeout for command '${descriptor.name}'.")
                }
            }
        }.start()
    }

    private fun showInfoAlert(title: String, content: String) {
        Alert(Alert.AlertType.INFORMATION).apply {
            this.title = title; this.headerText = null; this.contentText = content; this.showAndWait()
        }
    }
    private fun showErrorAlert(title: String, content: String) {
        Alert(Alert.AlertType.ERROR).apply {
            this.title = title; this.headerText = null; this.contentText = content; this.showAndWait()
        }
    }
}