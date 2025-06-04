package gui

// --- Импорты: Объединяем из обеих версий ---
import app.MainApp
import common.ArgumentType // Из второй версии (использовался в showArgumentInputDialog)
import common.CommandArgument // Из первой (для showArgumentInputDialog)
import common.CommandDescriptor
import common.Request
import core.ApiClient
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.fxml.FXML
import javafx.geometry.Insets // Из первой (для showArgumentInputDialog)
import javafx.scene.canvas.Canvas // Из второй
import javafx.scene.control.*
import javafx.scene.control.cell.PropertyValueFactory
import javafx.scene.layout.GridPane // Из первой (для showArgumentInputDialog)
import javafx.scene.layout.Pane // Из второй
import javafx.scene.layout.VBox
import javafx.scene.text.Font
import javafx.stage.FileChooser // Из первой (оставляем)
import javafx.stage.Stage
import model.FuelType
import model.Vehicle
import model.VehicleType
// javafx.scene.control.Dialog и ButtonBar.ButtonData уже были в первой, оставляем

class MainController {

    // --- @FXML Поля: Берем из ВТОРОЙ версии (она новее и содержит поля для карты) ---
    @FXML private lateinit var mapTab: Tab
    @FXML private lateinit var mapPane: Pane
    @FXML private lateinit var mapCanvas: Canvas
    @FXML private lateinit var commandsVBox: VBox
    @FXML private lateinit var currentUserLabel: Label
    @FXML private lateinit var connectionStatusLabel: Label
    @FXML private lateinit var logoutButton: Button

    // Поля для TableView (одинаковы в обеих версиях, берем любые)
    @FXML private lateinit var vehicleTableView: TableView<Vehicle>
    @FXML private lateinit var idColumn: TableColumn<Vehicle, Int>
    @FXML private lateinit var nameColumn: TableColumn<Vehicle, String>
    @FXML private lateinit var coordXColumn: TableColumn<Vehicle, Int>
    @FXML private lateinit var coordYColumn: TableColumn<Vehicle, Float>
    @FXML private lateinit var creationDateColumn: TableColumn<Vehicle, Long>
    @FXML private lateinit var enginePowerColumn: TableColumn<Vehicle, Double>
    @FXML private lateinit var distanceColumn: TableColumn<Vehicle, Double?>
    @FXML private lateinit var typeColumn: TableColumn<Vehicle, VehicleType?>
    @FXML private lateinit var fuelTypeColumn: TableColumn<Vehicle, FuelType?>
    @FXML private lateinit var userIdColumn: TableColumn<Vehicle, Int>

    // --- Остальные поля: Берем из ВТОРОЙ версии ---
    private lateinit var apiClient: ApiClient
    private lateinit var mainApp: MainApp
    private lateinit var currentStage: Stage
    private lateinit var mapVisualizationManager: MapVisualizationManager

    private val commandRegistry = mutableMapOf<String, CommandDescriptor>()
    private var mapDataLoadedAtLeastOnce = false // Из второй
    private val vehicleData: ObservableList<Vehicle> = FXCollections.observableArrayList()

    // --- Метод initialize(): Берем из ВТОРОЙ версии (с логикой карты), добавим очистку commandsVBox ---
    fun initialize() {
        println("MainController: initialize() called.")
        commandsVBox.children.clear() // Добавлено из первой версии
        logoutButton.isDisable = true

        setupTableColumns() // Было и там, и там, содержимое setupTableColumns берем из второй
        vehicleTableView.items = vehicleData

        Platform.runLater {
            if (::mapCanvas.isInitialized) {
                mapVisualizationManager = MapVisualizationManager(mapCanvas) { clickedVehicle ->
                    showVehicleInfo(clickedVehicle)
                }
                if (::mapPane.isInitialized) {
                    mapCanvas.widthProperty().bind(mapPane.widthProperty())
                    mapCanvas.heightProperty().bind(mapPane.heightProperty())
                    mapCanvas.widthProperty().addListener { _ -> mapVisualizationManager.redrawAll() }
                    mapCanvas.heightProperty().addListener { _ -> mapVisualizationManager.redrawAll() }

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

    // --- Метод setupTableColumns(): Берем из ВТОРОЙ версии (с SimpleIntegerProperty и т.д.) ---
    private fun setupTableColumns() {
        idColumn.cellValueFactory = PropertyValueFactory("id")
        nameColumn.cellValueFactory = PropertyValueFactory("name")
        // Используем cellValueFactory для координат из второй версии, так как они корректно работают с геттерами
        // или напрямую с полями объекта Coordinates, если PropertyValueFactory не справляется с вложенностью
        coordXColumn.setCellValueFactory { cellData -> javafx.beans.property.SimpleIntegerProperty(cellData.value.coordinates.x).asObject() }
        coordYColumn.setCellValueFactory { cellData -> javafx.beans.property.SimpleFloatProperty(cellData.value.coordinates.y).asObject() }
        creationDateColumn.cellValueFactory = PropertyValueFactory("creationDate") // TODO: Format
        enginePowerColumn.cellValueFactory = PropertyValueFactory("enginePower")
        distanceColumn.cellValueFactory = PropertyValueFactory("distanceTravelled")
        typeColumn.cellValueFactory = PropertyValueFactory("type")
        fuelTypeColumn.cellValueFactory = PropertyValueFactory("fuelType")
        userIdColumn.cellValueFactory = PropertyValueFactory("userId")
    }

    // --- Метод initialMapAndTableLoad(): Из ВТОРОЙ версии ---
    private fun initialMapAndTableLoad() {
        println("MainController: initialMapAndTableLoad. Canvas ready. ApiClient init: ${::apiClient.isInitialized}")
        if (!::apiClient.isInitialized) return

        if (apiClient.isConnected() && apiClient.getCurrentUserCredentials() != null) {
            fetchAndDisplayMapObjects(animate = !mapDataLoadedAtLeastOnce)
            refreshVehicleTableData()
        }
    }

    // --- Методы setApiClient, setMainApp, setCurrentStage, userLoggedIn:
    // В userLoggedIn() берем логику из ВТОРОЙ версии (с fetchAndDisplayMapObjects)
    // Остальные идентичны или почти идентичны, оставляем как есть (из любой версии).
    fun setApiClient(apiClient: ApiClient) {
        println("MainController: setApiClient() called.")
        this.apiClient = apiClient
        setupApiClientListeners()
        // В первой версии было:
        // val cachedDescriptors = apiClient.getCachedCommandDescriptors()
        // if (cachedDescriptors != null) { ... commandRegistry.clear() ... }
        // Во второй:
        apiClient.getCachedCommandDescriptors()?.let { updateCommandRegistryAndDisplay(it) } // Это более Kotlin-way
        refreshUIState()
    }

    fun setMainApp(mainApp: MainApp) { this.mainApp = mainApp }
    fun setCurrentStage(stage: Stage) { this.currentStage = stage }

    fun userLoggedIn() { // Из ВТОРОЙ ВЕРСИИ
        println("MainController: userLoggedIn() signal received.")
        refreshUIState()
        if (apiClient.isConnected()) {
            fetchAndDisplayMapObjects(animate = !mapDataLoadedAtLeastOnce)
            refreshVehicleTableData()
        }
    }

    // --- Метод refreshUIState(): Берем из ВТОРОЙ версии (с mapVisualizationManager.redrawAll()) ---
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

            updateCommandDisplayItself()
            if(::mapVisualizationManager.isInitialized) { // Проверка перед вызовом
                mapVisualizationManager.redrawAll()
            }

            // Логика из первой версии для обновления таблицы, если она пуста, уже есть в userLoggedIn и onConnectionStatusChanged
            // if (creds != null && apiClient.isConnected() && vehicleData.isEmpty()) {
            //    refreshVehicleTableData()
            // }
        }
    }

    // --- Метод setupApiClientListeners(): Берем из ВТОРОЙ версии (с fetchAndDisplayMapObjects при реконнекте) ---
    private fun setupApiClientListeners() {
        apiClient.onCommandDescriptorsUpdated = { descriptorsFromServer ->
            Platform.runLater {
                println("MainController: Listener onCommandDescriptorsUpdated received ${descriptorsFromServer.size} descriptors.")
                updateCommandRegistryAndDisplay(descriptorsFromServer)
                // Логика обновления таблицы из первой версии (если команды пришли, а таблица пуста)
                // val creds = apiClient.getCurrentUserCredentials()
                // if (creds != null && apiClient.isConnected() && vehicleData.isEmpty() && commandRegistry.isNotEmpty()) {
                //    refreshVehicleTableData() // Эта логика уже есть в onConnectionStatusChanged и userLoggedIn, возможно, здесь избыточна
                // }
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
                        if (::mapVisualizationManager.isInitialized) mapVisualizationManager.replaceAllVehicles(emptyList())
                        vehicleData.clear()
                        mapDataLoadedAtLeastOnce = false
                    }
                } else {
                    println("MainController: Connection lost.")
                }
                refreshUIState()
            }
        }
    }

    // --- Метод updateCommandRegistryAndDisplay(): Из ВТОРОЙ версии ---
    private fun updateCommandRegistryAndDisplay(descriptors: List<CommandDescriptor>) {
        commandRegistry.clear()
        descriptors.forEach { commandRegistry[it.name.lowercase()] = it }
        println("MainController: commandRegistry updated. New size: ${commandRegistry.size}")
        updateCommandDisplayItself()
    }

    // --- Метод updateCommandDisplayItself(): Логика почти идентична, берем из ВТОРОЙ (с Platform.runLater)
    // В первой версии не было Platform.runLater, но он здесь уместен.
    // Добавление execute_script идентично в обеих.
    private fun updateCommandDisplayItself() {
        Platform.runLater { // Из второй версии
            println("MainController: updateCommandDisplayItself. commandRegistry size: ${commandRegistry.size}, User: ${apiClient.getCurrentUserCredentials()?.first}")
            commandsVBox.children.clear()
            val currentUser = apiClient.getCurrentUserCredentials()
            // Логика displayableDescriptors из ПЕРВОЙ версии (она чуть полнее с учетом execute_script)
            val displayableDescriptors = mutableListOf<CommandDescriptor>()
            if (currentUser != null) {
                displayableDescriptors.addAll(this.commandRegistry.values)
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
                    currentUser == null && apiClient.isConnected() -> "Connected. Please login to see commands." // Из второй
                    currentUser != null && apiClient.isConnected() -> "Connected. Loading commands or no commands available..." // Из второй
                    else -> "Commands unavailable." // Из второй
                }
                commandsVBox.children.add(Label(placeholderText).apply { font = Font.font("Tahoma", 15.0) })
                return@runLater
            }

            println("MainController: Creating ${displayableDescriptors.size} command buttons.") // Из первой
            displayableDescriptors.sortedBy { it.name }.forEach { desc ->
                val button = Button(desc.name.replaceFirstChar { it.titlecase() }) // titlecase() из второй, но это мелкое отличие
                button.maxWidth = Double.MAX_VALUE; button.prefHeight = 40.0; button.isWrapText = true
                button.font = Font.font("Tahoma", 14.0); button.tooltip = Tooltip(desc.description)
                button.setOnAction { handleCommandExecution(desc) }
                commandsVBox.children.add(button)
            }
        }
    }

    // --- Метод fetchAndDisplayMapObjects(): Из ВТОРОЙ версии (заглушка createTestVehicles там же) ---
    private fun fetchAndDisplayMapObjects(animate: Boolean = false) {
        val currentUserCreds = apiClient.getCurrentUserCredentials()
        if (currentUserCreds == null || !apiClient.isConnected()) {
            if(::mapVisualizationManager.isInitialized) mapVisualizationManager.replaceAllVehicles(emptyList())
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
                if (response?.vehicles != null && !response.responseText.lowercase().contains("error:")) { // Используем response.vehicles
                    val vehiclesFromServer = response.vehicles
                    if (effectiveAnimate) {
                        if(::mapVisualizationManager.isInitialized) mapVisualizationManager.replaceAllVehicles(emptyList())
                        vehiclesFromServer.forEach { if(::mapVisualizationManager.isInitialized) mapVisualizationManager.addVehicleAnimated(it) }
                        if (vehiclesFromServer.isNotEmpty()) mapDataLoadedAtLeastOnce = true
                    } else {
                        if(::mapVisualizationManager.isInitialized) mapVisualizationManager.replaceAllVehicles(vehiclesFromServer)
                        if (vehiclesFromServer.isNotEmpty() && !mapDataLoadedAtLeastOnce) mapDataLoadedAtLeastOnce = true
                    }
                } else if (response != null && response.responseText.contains("Collection is empty", ignoreCase = true)) {
                    if(::mapVisualizationManager.isInitialized) mapVisualizationManager.replaceAllVehicles(emptyList())
                    mapDataLoadedAtLeastOnce = false // Коллекция пуста, значит не загружено
                }
                else {
                    val errorDetail = response?.responseText ?: "No response or timeout."
                    showErrorAlert("Map Data Error", "Failed to fetch vehicle data: $errorDetail")
                    if(::mapVisualizationManager.isInitialized) mapVisualizationManager.replaceAllVehicles(emptyList())
                }
            }
        }.start()
    }

    // --- Метод handleLogout(): Из ВТОРОЙ версии (с mapVisualizationManager и mapDataLoadedAtLeastOnce) ---
    @FXML
    private fun handleLogout() {
        println("Logout button clicked by ${apiClient.getCurrentUserCredentials()?.first ?: "Guest"}")
        apiClient.clearCurrentUserCredentials()
        this.commandRegistry.clear()
        if(::mapVisualizationManager.isInitialized) mapVisualizationManager.replaceAllVehicles(emptyList())
        vehicleData.clear()
        mapDataLoadedAtLeastOnce = false
        refreshUIState()
        mainApp.onLogout(currentStage)
    }

    // --- Метод refreshVehicleTableData(): Из ВТОРОЙ версии (с более детальной обработкой response.vehicles) ---
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
                if (response?.vehicles != null && !response.responseText.lowercase().contains("error:")) {
                    println("MainController: Received ${response.vehicles.size} vehicles for table from server.")
                    updateTableWithVehicles(response.vehicles)
                } else if (response != null && response.responseText.contains("Collection is empty", ignoreCase = true)) {
                    println("MainController: Server reports collection is empty for table.")
                    updateTableWithVehicles(emptyList())
                }
                else {
                    showErrorAlert("Table Update Error", "Failed to retrieve vehicle data. Response: ${response?.responseText}")
                    vehicleData.clear() // Очищаем при ошибке
                }
            }
        }.start()
    }

    // --- Метод updateTableWithVehicles(): Из ВТОРОЙ версии (с setAll) ---
    private fun updateTableWithVehicles(vehicles: List<Vehicle>) { // vehicles не nullable во второй версии, это лучше
        vehicleData.setAll(vehicles) // setAll эффективнее чем clear + addAll
        println("MainController: TableView updated with ${vehicles.size} items.")
    }

    // --- Метод showVehicleInfo(): Из ВТОРОЙ версии ---
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

    // --- Метод createTestVehicles(): Из ВТОРОЙ версии (удалим его, если он не нужен для реальной работы) ---
    // private fun createTestVehicles(username: String): List<Vehicle> { ... }

    // --- Методы showInfoAlert, showErrorAlert: Идентичны, оставляем ---
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

    // --- Метод handleCommandExecution(): ОБЪЕДИНЯЕМ ---
    private fun handleCommandExecution(descriptor: CommandDescriptor) {
        println("UI: Command button clicked: ${descriptor.name}")

        val currentCreds = apiClient.getCurrentUserCredentials()
        if (currentCreds == null) {
            showErrorAlert("Authentication Error", "You must be logged in to execute this command.")
            return
        }

        if (!apiClient.isConnected()) {
            // Код для попытки реконнекта (одинаков в обеих версиях)
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

        // --- Сбор аргументов команды: Берем из ПЕРВОЙ версии (с showArgumentInputDialog) ---
        var collectedArgs: List<String>? = null
        // Фильтр аргументов из ПЕРВОЙ версии (более точный, чем ArgumentType.NO_ARGS во второй)
        val argumentsToAskFor = descriptor.arguments.filter {
            it.type != common.ArgumentType.NO_ARGS && !it.isOptional
        }
        if (argumentsToAskFor.isNotEmpty()) {
            collectedArgs = showArgumentInputDialog(descriptor.name, argumentsToAskFor)
            if (collectedArgs == null) {
                showInfoAlert("Cancelled", "Command '${descriptor.name}' execution cancelled by user (argument input).")
                return
            }
        } else {
            collectedArgs = emptyList()
            println("Command '${descriptor.name}' does not require mandatory argument input.")
        }

        // --- Сбор объекта Vehicle: Берем из ПЕРВОЙ версии (с заглушкой VehicleInputDialog) ---
        var vehicleForRequest: Vehicle? = null
        if (descriptor.requiresVehicleObject) {
            println("Command '${descriptor.name}' requires Vehicle object. Showing Vehicle form...")
            // TODO: Здесь должен быть вызов вашего реального VehicleInputDialog
            val vehicleDialog = VehicleInputDialog(currentStage, null) // Используем класс, который мы обсуждали
            val resultVehicle = vehicleDialog.showAndWaitWithResult()
            if (resultVehicle != null) {
                vehicleForRequest = resultVehicle
            } else {
                showInfoAlert("Cancelled", "Vehicle input cancelled for command '${descriptor.name}'.")
                return
            }
        }

        // --- Формирование Request: Идентично ---
        val request = Request(
            body = listOf(descriptor.name) + (collectedArgs ?: emptyList()),
            vehicle = vehicleForRequest,
            username = currentCreds.first,
            password = currentCreds.second
        )

        // --- Отправка запроса и обработка ответа: Берем логику из ВТОРОЙ версии (с обновлением карты) ---
        showInfoAlert("Processing", "Sending command '${descriptor.name}' to server...")
        Thread {
            val response = apiClient.sendRequestAndWaitForResponse(request)
            Platform.runLater {
                if (response != null) {
                    showInfoAlert("Server Response - ${descriptor.name}", response.responseText)

                    if (response.responseText.contains("Authentication failed", ignoreCase = true)) {
                        apiClient.clearCurrentUserCredentials()
                        // refreshUIState() // refreshUIState будет вызван из mainApp.onLogout
                        mainApp.onLogout(currentStage)
                    } else if (!response.responseText.lowercase().startsWith("error")) {
                        val commandsThatModifyData = setOf(
                            "add", "add_if_max", "add_if_min", "update_id", "update", // Добавил "update"
                            "remove_by_id", "remove_first", "remove_any_by_engine_power", "clear",
                            "remove_greater", "remove_lower" // Добавил из первой версии
                            // execute_script обрабатывается отдельно
                        )
                        val commandNameLower = descriptor.name.lowercase()

                        if (commandsThatModifyData.contains(commandNameLower) || commandNameLower == "execute_script") {
                            println("Command ${descriptor.name} might have changed data. Refreshing map and table.")
                            fetchAndDisplayMapObjects(animate = false)
                            refreshVehicleTableData()
                        } else if (commandNameLower == "show") {
                            if (response.vehicles != null) {
                                updateTableWithVehicles(response.vehicles)
                                if(::mapVisualizationManager.isInitialized) mapVisualizationManager.replaceAllVehicles(response.vehicles)
                                mapDataLoadedAtLeastOnce = response.vehicles.isNotEmpty()
                            } else if (response.responseText.contains("Collection is empty", ignoreCase = true)) {
                                updateTableWithVehicles(emptyList())
                                if(::mapVisualizationManager.isInitialized) mapVisualizationManager.replaceAllVehicles(emptyList())
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


    // --- Методы showArgumentInputDialog и showDialogValidationError: Копируем из ПЕРВОЙ версии ---
    private fun showArgumentInputDialog(commandName: String, argumentsToAskFor: List<common.CommandArgument>): List<String>? {
        if (argumentsToAskFor.isEmpty()) {
            return emptyList()
        }

        val dialog = Dialog<List<String>>()
        dialog.initOwner(currentStage) // Привязка к currentStage
        dialog.title = "Input for $commandName"
        dialog.headerText = "Please enter arguments for command: $commandName"

        val okButtonType = ButtonType("OK", ButtonBar.ButtonData.OK_DONE)
        dialog.dialogPane.buttonTypes.addAll(okButtonType, ButtonType.CANCEL)

        val grid = GridPane().apply {
            hgap = 10.0
            vgap = 10.0
            padding = Insets(20.0, 20.0, 10.0, 20.0)
        }

        val inputFields = mutableListOf<TextField>()

        argumentsToAskFor.forEachIndexed { index, argDesc ->
            grid.add(Label("${argDesc.name} (${argDesc.type.name.lowercase()}):"), 0, index)
            val textField = TextField().apply {
                promptText = argDesc.description ?: argDesc.name
            }
            grid.add(textField, 1, index)
            inputFields.add(textField)
        }

        dialog.dialogPane.content = grid
        Platform.runLater { inputFields.firstOrNull()?.requestFocus() }

        dialog.setResultConverter { dialogButton ->
            if (dialogButton == okButtonType) {
                val enteredValues = mutableListOf<String>()
                for ((i, textField) in inputFields.withIndex()) {
                    val argDesc = argumentsToAskFor[i]
                    val value = textField.text.trim()
                    if (value.isEmpty() && !argDesc.isOptional) { // !isOptional важно, если мы решим запрашивать опциональные
                        showDialogValidationError("Argument '${argDesc.name}' is required and cannot be empty.", dialog)
                        return@setResultConverter null
                    }
                    when (argDesc.type) {
                        common.ArgumentType.INTEGER -> {
                            try { value.toInt() } catch (e: NumberFormatException) {
                                showDialogValidationError("Argument '${argDesc.name}' must be a valid integer. You entered: '$value'", dialog)
                                return@setResultConverter null
                            }
                        }
                        common.ArgumentType.DOUBLE -> {
                            try { value.toDouble() } catch (e: NumberFormatException) {
                                showDialogValidationError("Argument '${argDesc.name}' must be a valid number (double). You entered: '$value'", dialog)
                                return@setResultConverter null
                            }
                        }
                        common.ArgumentType.STRING, common.ArgumentType.NO_ARGS -> { /* NO_ARGS сюда не попадут из-за фильтра */ }
                    }
                    enteredValues.add(value)
                }
                enteredValues
            } else null
        }
        return dialog.showAndWait().orElse(null)
    }

    private fun showDialogValidationError(message: String, ownerDialog: Dialog<*>) {
        Alert(Alert.AlertType.ERROR).apply {
            initOwner(ownerDialog.dialogPane.scene.window)
            title = "Validation Error"
            headerText = "Invalid input"
            contentText = message
        }.showAndWait()
    }
}