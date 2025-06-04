package gui

import app.MainApp
import common.CommandDescriptor
import common.Request
import core.ApiClient
import javafx.application.Platform
import javafx.fxml.FXML
import javafx.scene.canvas.Canvas
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.Alert
import javafx.scene.control.TextInputDialog // Для примера сбора аргументов
import javafx.scene.control.Tooltip
import javafx.scene.layout.Pane
import javafx.scene.layout.VBox
import javafx.scene.text.Font
import javafx.stage.FileChooser // Для будущего execute_script
import javafx.stage.Stage
import model.Vehicle // Для handleCommandExecution

class MainController {

    @FXML
    private lateinit var mapCanvas: Canvas

    @FXML
    private lateinit var commandsVBox: VBox

    @FXML
    private lateinit var currentUserLabel: Label

    @FXML
    private lateinit var connectionStatusLabel: Label

    @FXML
    private lateinit var logoutButton: Button

    @FXML
    private lateinit var mapPane: Pane

    private lateinit var apiClient: ApiClient
    private lateinit var mainApp: MainApp
    private lateinit var currentStage: Stage
    private lateinit var mapVisualizationManager: MapVisualizationManager

    private val commandRegistry = mutableMapOf<String, CommandDescriptor>()
    private var mapDataLoadedAtLeastOnce = false
    private var vehiclesOnMap = listOf<Vehicle>()

    fun initialize() {
        println("MainController: initialize() called.")
        commandsVBox.children.clear()
        logoutButton.isDisable = true

        mapVisualizationManager = MapVisualizationManager(mapCanvas) { clickedVehicle ->
            showVehicleInfo(clickedVehicle)
        }

        // Привязка размеров Canvas к Pane
        Platform.runLater { // Откладываем, чтобы Pane успел получить размеры
            if (::mapPane.isInitialized && ::mapCanvas.isInitialized) { // Проверка инициализации
                mapCanvas.widthProperty().bind(mapPane.widthProperty())
                mapCanvas.heightProperty().bind(mapPane.heightProperty())
                mapCanvas.widthProperty().addListener { _ -> mapVisualizationManager.redrawAll() }
                mapCanvas.heightProperty().addListener { _ -> mapVisualizationManager.redrawAll() }
            } else {
                println("MainController: mapPane or mapCanvas not initialized in Platform.runLater of initialize.")
            }
        }
    }

    fun setApiClient(apiClient: ApiClient) {
        println("MainController: setApiClient() called.")
        this.apiClient = apiClient
        setupApiClientListeners()
        // Первоначальное обновление UI на основе кэшированных данных (если есть) и текущего статуса
        val cachedDescriptors = apiClient.getCachedCommandDescriptors()
        if (cachedDescriptors != null) {
            updateCommandRegistryAndDisplay(cachedDescriptors)
        }
        refreshUserAndConnectionStatus() // Обновит метки и кнопку logout
        // Загрузка карты произойдет при userLoggedIn или onConnectionStatusChanged
    }

    fun setMainApp(mainApp: MainApp) {
        this.mainApp = mainApp
    }

    fun setCurrentStage(stage: Stage) {
        this.currentStage = stage
    }

    // Вызывается из MainApp.showMainWindow()
    fun userLoggedIn() {
        println("MainController: userLoggedIn() signal received.")
        refreshUserAndConnectionStatus() // Обновит имя пользователя, кнопку logout
        if (apiClient.isConnected()) {
            if (mapVisualizationManager.getDisplayedObjectsCount() == 0 || !mapDataLoadedAtLeastOnce) { // Условие для первой загрузки
                fetchAndDisplayMapObjects(animate = true)
            }
        }
    }

    private fun refreshUserAndConnectionStatus() { // Этот метод теперь часть refreshUIState
        if (!::apiClient.isInitialized) return
        Platform.runLater {
            println("MainController: Refreshing User/Connection Status. Connected: ${apiClient.isConnected()}, User: ${apiClient.getCurrentUserCredentials()?.first}")
            val creds = apiClient.getCurrentUserCredentials()
            currentUserLabel.text = if (creds != null) "User: ${creds.first}" else "User: Not logged in"
            logoutButton.isDisable = creds == null
            connectionStatusLabel.text = if (apiClient.isConnected()) "Connection: Connected" else "Connection: Disconnected"

            if (!apiClient.isConnected()) {
                updateCommandDisplayItself() // Покажет "Not connected..." плейсхолдер для команд
                mapVisualizationManager.replaceAllVehicles(emptyList())
                mapDataLoadedAtLeastOnce = false
            }
        }
    }

    private fun refreshUIState() {
        if (!::apiClient.isInitialized) { return }
        Platform.runLater {
            println("MainController: Refreshing UI State. ApiClient connected: ${apiClient.isConnected()}, User: ${apiClient.getCurrentUserCredentials()?.first}, Commands in registry: ${commandRegistry.size}")
            val creds = apiClient.getCurrentUserCredentials()

            currentUserLabel.text = if (creds != null) "User: ${creds.first}" else "User: Not logged in"
            logoutButton.isDisable = creds == null
            connectionStatusLabel.text = if (apiClient.isConnected()) "Connection: Connected" else "Connection: Disconnected"

            updateCommandDisplayItself()
            mapVisualizationManager.redrawAll()
        }
    }

    private fun setupApiClientListeners() {
        apiClient.onCommandDescriptorsUpdated = { descriptorsFromServer ->
            Platform.runLater {
                println("MainController: Listener onCommandDescriptorsUpdated received ${descriptorsFromServer.size} descriptors.")
                commandRegistry.clear()
                descriptorsFromServer.forEach { commandRegistry[it.name.lowercase()] = it }
                println("MainController: commandRegistry updated by listener. New size: ${commandRegistry.size}")
                updateCommandDisplayItself()
            }
        }

        apiClient.onConnectionStatusChanged = { isConnected, message ->
            Platform.runLater {
                // refreshUserAndConnectionStatus() будет вызван внутри refreshUIState, который вызывается ниже
                // val statusMsg = message ?: if (isConnected) "Connection established." else "Disconnected."
                // println("MainController: Listener onConnectionStatusChanged. Connected: $isConnected, Message: $statusMsg")

                if (isConnected) {
                    val currentCredsInApiClient = apiClient.getCurrentUserCredentials()
                    if (currentCredsInApiClient != null) {
                        println("MainController: Connection (re-)established for ${currentCredsInApiClient.first}.")
                        fetchAndDisplayMapObjects(animate = !mapDataLoadedAtLeastOnce)
                    } else {
                        println("MainController: Connection established. User not logged in.")
                        mapVisualizationManager.replaceAllVehicles(emptyList())
                        mapDataLoadedAtLeastOnce = false
                        // updateCommandDisplayItself() будет вызван из refreshUIState
                    }
                } else {
                    println("MainController: Connection lost.")
                }
                refreshUIState() // Обновляем весь UI
            }
        }
    }

    private fun updateCommandRegistryAndDisplay(descriptors: List<CommandDescriptor>) {
        commandRegistry.clear()
        descriptors.forEach { commandRegistry[it.name.lowercase()] = it }
        println("MainController: commandRegistry updated. New size: ${commandRegistry.size}")
        updateCommandDisplayItself()
    }

    private fun updateCommandDisplayItself() {
        Platform.runLater { // Убедимся, что в UI потоке
            println("MainController: updateCommandDisplayItself. commandRegistry size: ${commandRegistry.size}, User: ${apiClient.getCurrentUserCredentials()?.first}")
            commandsVBox.children.clear()

            val displayableCommandDescriptors = mutableListOf<CommandDescriptor>() // ИСПРАВЛЕНИЕ 5: Правильное имя переменной
            val currentUser = apiClient.getCurrentUserCredentials()

            val displayableItems = mutableListOf<CommandDescriptor>()
            displayableItems.addAll(displayableCommandDescriptors)

            if (currentUser != null) {
                displayableCommandDescriptors.addAll(this.commandRegistry.values)
                if (this.commandRegistry.values.none { it.name.equals("execute_script", ignoreCase = true) }) {
                    displayableCommandDescriptors.add(
                    CommandDescriptor(
                        name = "execute_script",
                        description = "Execute commands from a script file.",
                        arguments = listOf(
                            common.CommandArgument(
                                "filename",
                                common.ArgumentType.STRING,
                                false,
                                "Path to the script file"
                            )
                        ),
                        requiresVehicleObject = false
                    )
                )
            }
}
            if (displayableItems.isEmpty()) {
                val placeholderText = when {
                    !apiClient.isConnected() -> "Not connected. Commands unavailable."
                    currentUser == null -> "Connected. Please login to see commands."
                    else -> "Connected. Loading commands or no commands available..."
                }
                commandsVBox.children.add(Label(placeholderText).apply { font = Font.font("Tahoma", 15.0) })
                return@runLater
            }

            displayableItems.sortedBy { it.name }.forEach { desc ->
                val button = Button(desc.name.replaceFirstChar { it.titlecase() })
                button.maxWidth = Double.MAX_VALUE
                button.prefHeight = 40.0
                button.isWrapText = true
                button.font = Font.font("Tahoma", 14.0)
                button.tooltip = Tooltip(desc.description)
                button.setOnAction { handleCommandExecution(desc) }
                commandsVBox.children.add(button)
            }
        }
    }

    @FXML
    private fun handleLogout() {
        println("Logout button clicked by ${apiClient.getCurrentUserCredentials()?.first ?: "Guest"}")
        apiClient.clearCurrentUserCredentials() // Это вызовет onCommandDescriptorsUpdated(emptyList) в ApiClient
        this.commandRegistry.clear() // Дополнительно очищаем здесь
        mapVisualizationManager.replaceAllVehicles(emptyList())
        mapDataLoadedAtLeastOnce = false
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
                        showInfoAlert(
                            "Connection",
                            "Reconnected. Please try your command '${descriptor.name}' again."
                        )
                        // После реконнекта ApiClient должен был запросить команды,
                        // onCommandDescriptorsUpdated обновит UI.
                    } else {
                        showErrorAlert(
                            "Connection Error",
                            "Failed to connect to server. Command '${descriptor.name}' not sent."
                        )
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
            showInfoAlert(
                "Input Required",
                "Command '${descriptor.name}' requires arguments. (GUI for this is a TODO)"
            )
            println("TODO: Implement GUI for argument input for command ${descriptor.name}")
            return // Заглушка: не выполняем команду, если нужны аргументы и нет GUI для их ввода
        }

        if (descriptor.requiresVehicleObject) {
            showInfoAlert(
                "Vehicle Input",
                "Command '${descriptor.name}' requires Vehicle data. (GUI for this is a TODO)"
            )
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

    private fun fetchAndDisplayMapObjects(animate: Boolean = false) {
        val currentUserCreds = apiClient.getCurrentUserCredentials()
        if (currentUserCreds == null || !apiClient.isConnected()) {
            mapVisualizationManager.replaceAllVehicles(emptyList())
            if (currentUserCreds == null) mapDataLoadedAtLeastOnce = false
            return
        }
        println("MainController: Fetching vehicles for map display... Animate: $animate, MapDataLoadedOnce: $mapDataLoadedAtLeastOnce")

        val effectiveAnimate = animate && !mapDataLoadedAtLeastOnce

        val showRequest = Request(
            body = listOf("show"),
            username = currentUserCreds.first,
            password = currentUserCreds.second
        )

        Thread {
            val response = apiClient.sendRequestAndWaitForResponse(showRequest)
            Platform.runLater {
                if (response != null && !response.responseText.lowercase().contains("error:")) {
                    val vehiclesFromServer = createTestVehicles(currentUserCreds.first) // ИСПРАВЛЕНИЕ 4: Восстанавливаем заглушку
                    this.vehiclesOnMap = vehiclesFromServer

                    println("MainController: Received ${vehiclesFromServer.size} vehicles. Effective Animate: $effectiveAnimate")
                    if (effectiveAnimate) {
                        mapVisualizationManager.replaceAllVehicles(emptyList())
                        vehiclesFromServer.forEach { mapVisualizationManager.addVehicleAnimated(it) }
                        if (vehiclesFromServer.isNotEmpty()) mapDataLoadedAtLeastOnce = true
                    } else {
                        mapVisualizationManager.replaceAllVehicles(vehiclesFromServer)
                        if (vehiclesFromServer.isNotEmpty() && !mapDataLoadedAtLeastOnce) mapDataLoadedAtLeastOnce = true
                    }
                } else {
                    val errorDetail = response?.responseText ?: "No response or timeout."
                    showErrorAlert("Map Data Error", "Failed to fetch vehicle data: $errorDetail")
                    mapVisualizationManager.replaceAllVehicles(emptyList())
                }
            }
        }.start()
    }

    private fun showVehicleInfo(vehicle: Vehicle) {
        val alert = Alert(Alert.AlertType.INFORMATION)
        alert.title = "Vehicle Information"
        alert.headerText = "Details for Vehicle ID: ${vehicle.id}"
        alert.contentText = """
            Name: ${vehicle.name}
            Owner ID: ${vehicle.userId}
            Coordinates: (X: ${vehicle.coordinates.x}, Y: ${vehicle.coordinates.y})
            Engine Power: ${vehicle.enginePower}
            Type: ${vehicle.type?.name ?: "N/A"}
            Fuel Type: ${vehicle.fuelType?.name ?: "N/A"}
            Distance Travelled: ${vehicle.distanceTravelled ?: "N/A"}
        """.trimIndent()
        alert.showAndWait()
    }

    private fun createTestVehicles(username: String): List<Vehicle> {
        val userId = username.hashCode().mod(10) + 1
        return listOf(
            Vehicle(1, "Tesla_Map", model.Coordinates(100, 150.0f), System.currentTimeMillis(), 200.0, 1000.0, model.VehicleType.BOAT, model.FuelType.NUCLEAR, userId),
            Vehicle(2, "Skoda_Map", model.Coordinates(300, 250.0f), System.currentTimeMillis(), 150.0, 500.0, model.VehicleType.BICYCLE, model.FuelType.MANPOWER, (userId + 1).mod(10)+1),
            Vehicle(3, "Hover_Map", model.Coordinates(500, 100.0f), System.currentTimeMillis(), 50.0, 100.0, model.VehicleType.HOVERBOARD, model.FuelType.ALCOHOL, userId)
        )
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