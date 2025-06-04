package gui

import app.MainApp
import common.ArgumentType
import common.CommandDescriptor
import common.Request
import core.ApiClient
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.fxml.FXML
import javafx.geometry.Insets
import javafx.scene.canvas.Canvas
import javafx.scene.control.*
import javafx.scene.control.cell.PropertyValueFactory
import javafx.scene.layout.GridPane
import javafx.scene.layout.Pane
import javafx.scene.layout.VBox
import javafx.scene.text.Font
import javafx.stage.Stage
import model.FuelType
import model.Vehicle
import model.VehicleType

class MainController {

    @FXML private lateinit var mapTab: Tab
    @FXML private lateinit var mapPane: Pane
    @FXML private lateinit var mapCanvas: Canvas
    @FXML private lateinit var commandsVBox: VBox
    @FXML private lateinit var currentUserLabel: Label
    @FXML private lateinit var connectionStatusLabel: Label
    @FXML private lateinit var logoutButton: Button

    // Поля для TableView
    @FXML private lateinit var vehicleTableView: TableView<Vehicle>
    @FXML private lateinit var idColumn: TableColumn<Vehicle, Int>
    @FXML private lateinit var nameColumn: TableColumn<Vehicle, String>
    @FXML private lateinit var coordXColumn: TableColumn<Vehicle, Int>
    @FXML private lateinit var coordYColumn: TableColumn<Vehicle, Float>
    @FXML private lateinit var creationDateColumn: TableColumn<Vehicle, Long> // нужно форматировании
    @FXML private lateinit var enginePowerColumn: TableColumn<Vehicle, Double>
    @FXML private lateinit var distanceColumn: TableColumn<Vehicle, Double?>
    @FXML private lateinit var typeColumn: TableColumn<Vehicle, VehicleType?>
    @FXML private lateinit var fuelTypeColumn: TableColumn<Vehicle, FuelType?>
    @FXML private lateinit var userIdColumn: TableColumn<Vehicle, Int>

    private lateinit var apiClient: ApiClient
    private lateinit var mainApp: MainApp
    private lateinit var currentStage: Stage
    private lateinit var mapVisualizationManager: MapVisualizationManager

    private val commandRegistry = mutableMapOf<String, CommandDescriptor>()
    private var mapDataLoadedAtLeastOnce = false
    private val vehicleData: ObservableList<Vehicle> = FXCollections.observableArrayList()

    fun initialize() {
        println("MainController: initialize() called.")
        commandsVBox.children.clear()
        logoutButton.isDisable = true

        setupTableColumns()
        vehicleTableView.items = vehicleData

        Platform.runLater { // Откладываем до момента, когда UI готов
            if (::mapCanvas.isInitialized) { // Проверяем, что FXML инъекция прошла
                mapVisualizationManager = MapVisualizationManager(mapCanvas) { clickedVehicle ->
                    showVehicleInfo(clickedVehicle)
                }

                if (::mapPane.isInitialized) {
                    mapCanvas.widthProperty().bind(mapPane.widthProperty())
                    mapCanvas.heightProperty().bind(mapPane.heightProperty())
                    mapCanvas.widthProperty().addListener { _ -> mapVisualizationManager.redrawAll() }
                    mapCanvas.heightProperty().addListener { _ -> mapVisualizationManager.redrawAll() }

                    // Первоначальная загрузка данных для карты, если сцена уже есть
                    if (mapCanvas.scene != null) {
                        initialMapAndTableLoad()
                    } else {
                        mapCanvas.sceneProperty().addListener { _, oldS, newS ->
                            if (oldS == null && newS != null) {
                                initialMapAndTableLoad()
                            }
                        }
                    }
                } else {
                    println("MainController: ERROR - mapPane was not initialized by FXML loader!")
                }
            } else {
                println("MainController: ERROR - mapCanvas was NOT initialized by FXML loader!")
            }
        }
    }

    private fun setupTableColumns() {
        idColumn.cellValueFactory = PropertyValueFactory("id")
        nameColumn.cellValueFactory = PropertyValueFactory("name")
        coordXColumn.setCellValueFactory { cellData -> javafx.beans.property.SimpleIntegerProperty(cellData.value.coordinates.x).asObject() }
        coordYColumn.setCellValueFactory { cellData -> javafx.beans.property.SimpleFloatProperty(cellData.value.coordinates.y).asObject() }
        creationDateColumn.cellValueFactory = PropertyValueFactory("creationDate") // TODO: Format to human-readable date
        enginePowerColumn.cellValueFactory = PropertyValueFactory("enginePower")
        distanceColumn.cellValueFactory = PropertyValueFactory("distanceTravelled")
        typeColumn.cellValueFactory = PropertyValueFactory("type")
        fuelTypeColumn.cellValueFactory = PropertyValueFactory("fuelType")
        userIdColumn.cellValueFactory = PropertyValueFactory("userId")
    }


    private fun initialMapAndTableLoad() {
        println("MainController: initialMapAndTableLoad. Canvas ready. ApiClient init: ${::apiClient.isInitialized}")
        if (!::apiClient.isInitialized) return

        if (apiClient.isConnected() && apiClient.getCurrentUserCredentials() != null) {
            fetchAndDisplayMapObjects(animate = !mapDataLoadedAtLeastOnce)
            refreshVehicleTableData()
        } else {
            // Если не подключены или не залогинены, карта и таблица будут обновлены через refreshUIState
            // mapVisualizationManager.redrawAll() // Покажет плейсхолдер карты
            // vehicleData.clear() // Очистит таблицу
        }
    }

    fun setApiClient(apiClient: ApiClient) {
        println("MainController: setApiClient() called.")
        this.apiClient = apiClient
        setupApiClientListeners()
        apiClient.getCachedCommandDescriptors()?.let { updateCommandRegistryAndDisplay(it) }
        refreshUIState()
    }

    fun setMainApp(mainApp: MainApp) { this.mainApp = mainApp }
    fun setCurrentStage(stage: Stage) { this.currentStage = stage }

    fun userLoggedIn() {
        println("MainController: userLoggedIn() signal received.")
        refreshUIState() // Обновит имя пользователя, статус, кнопки команд
        if (apiClient.isConnected()) {
            // Загружаем данные для карты и таблицы с анимацией для карты, если это первая загрузка
            fetchAndDisplayMapObjects(animate = !mapDataLoadedAtLeastOnce)
            refreshVehicleTableData()
        }
    }

    private fun refreshUIState() {
        if (!::apiClient.isInitialized) {
            println("MainController: refreshUIState - apiClient not initialized yet.")
            return
        }
        Platform.runLater {
            println("MainController: Refreshing UI State. Connected: ${apiClient.isConnected()}, User: ${apiClient.getCurrentUserCredentials()?.first}, Commands: ${commandRegistry.size}")
            val creds = apiClient.getCurrentUserCredentials()
            currentUserLabel.text = if (creds != null) "User: ${creds.first}" else "User: Not logged in"
            logoutButton.isDisable = creds == null
            connectionStatusLabel.text = if (apiClient.isConnected()) "Connection: Connected" else "Connection: Disconnected"

            updateCommandDisplayItself() // Обновляем кнопки команд
            mapVisualizationManager.redrawAll() // Обновляем карту
        }
    }


    private fun setupApiClientListeners() {
        apiClient.onCommandDescriptorsUpdated = { descriptorsFromServer ->
            Platform.runLater {
                println("MainController: Listener onCommandDescriptorsUpdated received ${descriptorsFromServer.size} descriptors.")
                updateCommandRegistryAndDisplay(descriptorsFromServer)
            }
        }

        apiClient.onConnectionStatusChanged = { isConnected, message ->
            Platform.runLater {
                val statusMsg = message ?: if (isConnected) "Connection established." else "Disconnected."
                println("MainController: Listener onConnectionStatusChanged. Connected: $isConnected, Message: $statusMsg")

                if (isConnected) {
                    val currentCreds = apiClient.getCurrentUserCredentials()
                    if (currentCreds != null) {
                        println("MainController: Connection (re-)established for ${currentCreds.first}.")
                        fetchAndDisplayMapObjects(animate = !mapDataLoadedAtLeastOnce)
                        refreshVehicleTableData()
                    } else {
                        println("MainController: Connection established, but user not logged in.")
                        mapVisualizationManager.replaceAllVehicles(emptyList())
                        vehicleData.clear()
                        mapDataLoadedAtLeastOnce = false
                    }
                } else { // Disconnected
                    println("MainController: Connection lost.")
                    // UI обновится через refreshUIState. Данные карты/таблицы остаются,
                    // если не решено их очищать при дисконнекте.
                }
                refreshUIState() // Обновляем весь UI
            }
        }
    }

    // Обновляет И реестр И отображение кнопок
    private fun updateCommandRegistryAndDisplay(descriptors: List<CommandDescriptor>) {
        commandRegistry.clear()
        descriptors.forEach { commandRegistry[it.name.lowercase()] = it }
        println("MainController: commandRegistry updated. New size: ${commandRegistry.size}")
        updateCommandDisplayItself()
    }

    // Только рисует кнопки на основе this.commandRegistry
    private fun updateCommandDisplayItself() {
        Platform.runLater {
            println("MainController: updateCommandDisplayItself. commandRegistry size: ${commandRegistry.size}, User: ${apiClient.getCurrentUserCredentials()?.first}")
            commandsVBox.children.clear()
            val currentUser = apiClient.getCurrentUserCredentials()
            val descriptorsToDisplay = if (currentUser != null) commandRegistry.values.toList() else emptyList()

            val displayableItems = mutableListOf<CommandDescriptor>()
            displayableItems.addAll(descriptorsToDisplay)

            if (currentUser != null && displayableItems.none { it.name.equals("execute_script", ignoreCase = true) }) {
                displayableItems.add(
                    CommandDescriptor(
                        name = "execute_script",
                        description = "Execute commands from a script file.",
                        arguments = listOf(common.CommandArgument("filename", ArgumentType.STRING, false, "Path to the script file")),
                        requiresVehicleObject = false
                    )
                )
            }

            if (displayableItems.isEmpty()) {
                val placeholderText = when {
                    !apiClient.isConnected() -> "Not connected. Commands unavailable."
                    currentUser == null && apiClient.isConnected() -> "Connected. Please login to see commands."
                    currentUser != null && apiClient.isConnected() -> "Connected. Loading commands or no commands available..."
                    else -> "Commands unavailable."
                }
                commandsVBox.children.add(Label(placeholderText).apply { font = Font.font("Tahoma", 15.0) })
                return@runLater
            }

            displayableItems.sortedBy { it.name }.forEach { desc ->
                val button = Button(desc.name.replaceFirstChar { it.titlecase() })
                button.maxWidth = Double.MAX_VALUE; button.prefHeight = 40.0; button.isWrapText = true
                button.font = Font.font("Tahoma", 14.0); button.tooltip = Tooltip(desc.description)
                button.setOnAction { handleCommandExecution(desc) }
                commandsVBox.children.add(button)
            }
        }
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
            body = listOf("show"), username = currentUserCreds.first, password = currentUserCreds.second
        )
        Thread {
            val response = apiClient.sendRequestAndWaitForResponse(showRequest)
            Platform.runLater {
                if (response != null && !response.responseText.lowercase().contains("error:")) {
                    // TODO: Заменить заглушку на реальный парсинг response.vehicles
                    val vehiclesFromServer = createTestVehicles(currentUserCreds.first)
                    // this.vehiclesOnMap = vehiclesFromServer // Если нужно хранить копию

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

    @FXML
    private fun handleLogout() {
        println("Logout button clicked by ${apiClient.getCurrentUserCredentials()?.first ?: "Guest"}")
        apiClient.clearCurrentUserCredentials()
        this.commandRegistry.clear()
        mapVisualizationManager.replaceAllVehicles(emptyList())
        vehicleData.clear()
        mapDataLoadedAtLeastOnce = false
        refreshUIState()
        mainApp.onLogout(currentStage)
    }

    private fun refreshVehicleTableData() {
        val currentUserCreds = apiClient.getCurrentUserCredentials()
        if (currentUserCreds == null || !apiClient.isConnected()) {
            println("Cannot refresh table: Not logged in or not connected.")
            vehicleData.clear()
            return
        }
        println("MainController: Requesting vehicle data for table (using 'show' command)...")
        val showRequest = Request(
            body = listOf("show"), username = currentUserCreds.first, password = currentUserCreds.second
        )
        Thread {
            val response = apiClient.sendRequestAndWaitForResponse(showRequest)
            Platform.runLater {
                if (response?.vehicles != null && !response.responseText.lowercase().contains("error:")) { // Предполагаем, что Response теперь имеет поле vehicles
                    println("MainController: Received ${response.vehicles.size} vehicles for table from server.")
                    updateTableWithVehicles(response.vehicles)
                } else if (response != null && response.responseText.contains("Collection is empty", ignoreCase = true)) {
                    println("MainController: Server reports collection is empty for table.")
                    updateTableWithVehicles(emptyList())
                }
                else {
                    showErrorAlert("Table Update Error", "Failed to retrieve vehicle data. Response: ${response?.responseText}")
                    vehicleData.clear()
                }
            }
        }.start()
    }

    private fun updateTableWithVehicles(vehicles: List<Vehicle>) {
        vehicleData.setAll(vehicles)
        println("MainController: TableView updated with ${vehicles.size} items.")
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

    private fun handleCommandExecution(descriptor: CommandDescriptor) {
        println("UI: Command button clicked: ${descriptor.name}")

        val currentCreds = apiClient.getCurrentUserCredentials()
        if (currentCreds == null) {
            showErrorAlert("Authentication Error", "You must be logged in to execute this command.")
            return
        }

        if (!apiClient.isConnected()) {
            showInfoAlert("Connection", "Not connected. Attempting to connect for command '${descriptor.name}'...")
            Thread {
                val connected = apiClient.connectIfNeeded()
                Platform.runLater {
                    if (connected) {
                        showInfoAlert("Connection", "Reconnected. Please try your command '${descriptor.name}' again.")
                    } else {
                        showErrorAlert("Connection Error", "Failed to connect to server. Command '${descriptor.name}' not sent.")
                    }
                }
            }.start()
            return
        }

        // 1. Сбор аргументов команды через GUI
        val argsList = mutableListOf<String>()
        if (descriptor.arguments.any { it.type != ArgumentType.NO_ARGS }) { // Если есть какие-либо аргументы (кроме NO_ARGS)
            val collectedArgs = showArgumentInputDialog(descriptor.name, descriptor.arguments)
            if (collectedArgs == null) { // Пользователь отменил ввод
                showInfoAlert("Cancelled", "Command '${descriptor.name}' execution cancelled by user (argument input).")
                return
            }
            argsList.addAll(collectedArgs)
        }

        // 2. Сбор объекта Vehicle через GUI, если требуется
        var vehicleForRequest: Vehicle? = null
        if (descriptor.requiresVehicleObject) {
            println("Command '${descriptor.name}' requires Vehicle object. Showing Vehicle form...")
            // TODO: Заменить это на вызов твоего реального VehicleFormDialog
            // val vehicleDialog = VehicleFormDialog(currentStage, null) // null для нового объекта
            // val resultVehicle = vehicleDialog.showAndWaitWithResult()
            // if (resultVehicle != null) {
            // vehicleForRequest = resultVehicle
            // } else {
            // showInfoAlert("Cancelled", "Vehicle input cancelled for command '${descriptor.name}'.")
            // return
            // }
            // ЗАГЛУШКА: Вместо диалога пока просто выведем сообщение и не будем отправлять команду,
            // так как интерактивный VehicleReader из консоли здесь не подойдет.
            showErrorAlert("Vehicle Input Required", "Command '${descriptor.name}' requires Vehicle data. GUI for vehicle input is not yet implemented.")
            return
        }

        // 3. Формирование Request
        // Имя команды для сервера берем из дескриптора (оно должно быть "чистым", без "+")
        // Сервер сам определит по своей конфигурации команды, нужен ли ему объект Vehicle из запроса.
        val request = Request(
            body = listOf(descriptor.name) + argsList,
            vehicle = vehicleForRequest,
            username = currentCreds.first,
            password = currentCreds.second
        )

        // 4. Отправка запроса и обработка ответа
        showInfoAlert("Processing", "Sending command '${descriptor.name}' to server...")
        Thread {
            val response = apiClient.sendRequestAndWaitForResponse(request)
            Platform.runLater {
                if (response != null) {
                    showInfoAlert("Server Response - ${descriptor.name}", response.responseText)

                    if (response.responseText.contains("Authentication failed", ignoreCase = true)) {
                        apiClient.clearCurrentUserCredentials()
                        refreshUIState()
                        mainApp.onLogout(currentStage)
                    } else if (!response.responseText.lowercase().startsWith("error")) {
                        // Команда выполнена успешно (не содержит "error:" в начале)
                        // Проверяем, нужно ли обновить данные
                        val commandsThatModifyData = setOf(
                            "add", "add_if_max", "add_if_min", "update_id", // Имена команд в lowercase
                            "remove_by_id", "remove_first", "remove_any_by_engine_power", "clear"
                            // Добавь сюда все команды, которые изменяют коллекцию на сервере
                        )
                        // execute_script тоже может изменять данные, но его результат сложнее предсказать
                        // для простого обновления. Возможно, для execute_script всегда делать полный рефреш.

                        if (commandsThatModifyData.contains(descriptor.name.lowercase()) || descriptor.name.lowercase() == "execute_script") {
                            println("Command ${descriptor.name} might have changed data. Refreshing map and table.")
                            fetchAndDisplayMapObjects(animate = false) // Обновить карту без анимации наложения
                            refreshVehicleTableData()                   // Обновить таблицу
                        } else if (descriptor.name.lowercase() == "show") {
                            // Если это была команда "show", и сервер прислал список vehicles в ответе
                            if (response.vehicles != null) {
                                updateTableWithVehicles(response.vehicles)
                                // Для карты можно использовать replaceAllVehicles или более умное обновление
                                mapVisualizationManager.replaceAllVehicles(response.vehicles)
                                mapDataLoadedAtLeastOnce = response.vehicles.isNotEmpty()
                            } else if (response.responseText.contains("Collection is empty", ignoreCase = true)) {
                                updateTableWithVehicles(emptyList())
                                mapVisualizationManager.replaceAllVehicles(emptyList())
                                mapDataLoadedAtLeastOnce = false
                            }
                        }
                    }
                } else {
                    showErrorAlert("Server Error", "No response or timeout for command '${descriptor.name}'.")
                }
            }
        }.start()
    }

    // Вспомогательный метод для диалога ввода аргументов
    private fun showArgumentInputDialog(commandName: String, argumentsToAsk: List<common.CommandArgument>): List<String>? {
        val mandatoryArgs = argumentsToAsk.filter { !it.isOptional && it.type != ArgumentType.NO_ARGS }
        if (mandatoryArgs.isEmpty()) { // Если только опциональные или NO_ARGS, не показываем диалог для обязательных
            // TODO: Можно добавить логику для опциональных аргументов, если нужно
            return emptyList()
        }

        val dialog = Dialog<List<String>>()
        dialog.initOwner(currentStage) // Привязываем к главному окну
        dialog.title = "Input for $commandName"
        dialog.headerText = "Please enter required arguments for command: $commandName"

        val okButtonType = ButtonType("OK", ButtonBar.ButtonData.OK_DONE)
        dialog.dialogPane.buttonTypes.addAll(okButtonType, ButtonType.CANCEL)

        val grid = GridPane().apply {
            hgap = 10.0; vgap = 10.0; padding = Insets(20.0, 150.0, 10.0, 10.0)
        }
        val inputFields = mutableListOf<TextField>()

        mandatoryArgs.forEachIndexed { index, argDesc ->
            grid.add(Label("${argDesc.name} (${argDesc.type.name.lowercase()}):"), 0, index)
            val textField = TextField().apply { promptText = argDesc.description ?: argDesc.name }
            grid.add(textField, 1, index)
            inputFields.add(textField)
        }
        dialog.dialogPane.content = grid
        Platform.runLater { inputFields.firstOrNull()?.requestFocus() }

        dialog.setResultConverter { dialogButton ->
            if (dialogButton == okButtonType) {
                val enteredValues = mutableListOf<String>()
                for ((i, textField) in inputFields.withIndex()) {
                    val argDesc = mandatoryArgs[i]
                    val value = textField.text.trim()
                    if (value.isEmpty()) { // Обязательные аргументы не могут быть пустыми
                        showDialogValidationError("Argument '${argDesc.name}' is required.", dialog)
                        return@setResultConverter null // Остаемся в диалоге
                    }
                    // TODO: Добавить более строгую валидацию типов (Integer, Double) здесь,
                    // аналогично тому, как было в консольном VehicleReader.
                    // Сейчас просто добавляем как строки.
                    enteredValues.add(value)
                }
                enteredValues
            } else null
        }
        return dialog.showAndWait().orElse(null)
    }

    // Вспомогательный метод для показа ошибок валидации в диалоге
    private fun showDialogValidationError(message: String, ownerDialog: Dialog<*>) {
        Alert(Alert.AlertType.ERROR).apply {
            initOwner(ownerDialog.dialogPane.scene.window)
            title = "Validation Error"
            headerText = "Invalid Input"
            contentText = message
        }.showAndWait()
    }
}