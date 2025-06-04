package gui

import app.MainApp
import common.ArgumentType // Убедись, что импорт есть
import common.CommandDescriptor
import common.Request
import core.ApiClient
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.fxml.FXML
import javafx.scene.canvas.Canvas
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.TextField
import javafx.scene.control.Tooltip
import javafx.scene.control.ButtonBar.ButtonData
import javafx.scene.layout.GridPane
import javafx.scene.layout.Pane // Для mapPane, если он нужен для биндинга Canvas
import javafx.scene.layout.VBox
import javafx.scene.text.Font
import javafx.stage.FileChooser
import javafx.stage.Stage
import javafx.geometry.Insets // Для GridPane
import javafx.scene.control.cell.PropertyValueFactory
import model.Coordinates // Для createTestVehicles и cellValueFactory
import model.FuelType
import model.Vehicle
import model.VehicleType
// import core.VehicleReader // Если будешь использовать для GUI ввода Vehicle

class MainController {

    // FXML Поля для основного UI
    @FXML private lateinit var commandsVBox: VBox
    @FXML private lateinit var currentUserLabel: Label
    @FXML private lateinit var connectionStatusLabel: Label
    @FXML private lateinit var logoutButton: Button

    // FXML Поля для карты
    @FXML private lateinit var mapPane: Pane // Родительский Pane для Canvas
    @FXML private lateinit var mapCanvas: Canvas

    // FXML Поля для TableView
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

        // Инициализация MapVisualizationManager должна происходить после того, как mapCanvas инъецирован.
        // Это гарантируется тем, что initialize() вызывается после инъекции @FXML.
        mapVisualizationManager = MapVisualizationManager(mapCanvas) { clickedVehicle ->
            showVehicleInfo(clickedVehicle)
        }

        // Привязка размеров Canvas к Pane и слушатели на изменение размеров
        Platform.runLater { // Откладываем, чтобы Pane гарантированно имел размеры после layout pass
            if (::mapPane.isInitialized && ::mapCanvas.isInitialized) {
                mapCanvas.widthProperty().bind(mapPane.widthProperty())
                mapCanvas.heightProperty().bind(mapPane.heightProperty())
                mapCanvas.widthProperty().addListener { _ -> mapVisualizationManager.redrawAll() }
                mapCanvas.heightProperty().addListener { _ -> mapVisualizationManager.redrawAll() }

                // Первоначальная загрузка данных для карты, если сцена уже есть
                // (может быть избыточно, если initialMapAndTableLoad вызывается позже)
                if (mapCanvas.scene != null) { initialMapAndTableLoad() }
                else { mapCanvas.sceneProperty().addListener { _, oldS, newS -> if (oldS == null && newS != null) initialMapAndTableLoad() } }
            } else {
                println("MainController: ERROR - mapPane or mapCanvas not initialized in Platform.runLater for binding.")
            }
        }
    }



    private fun setupTableColumns() {
        idColumn.cellValueFactory = PropertyValueFactory("id")
        nameColumn.cellValueFactory = PropertyValueFactory("name")
        coordXColumn.setCellValueFactory { cellData -> javafx.beans.property.SimpleIntegerProperty(cellData.value.coordinates.x).asObject() }
        coordYColumn.setCellValueFactory { cellData -> javafx.beans.property.SimpleFloatProperty(cellData.value.coordinates.y).asObject() }
        creationDateColumn.cellValueFactory = PropertyValueFactory("creationDate") // TODO: Форматирование даты
        enginePowerColumn.cellValueFactory = PropertyValueFactory("enginePower")
        distanceColumn.cellValueFactory = PropertyValueFactory("distanceTravelled")
        typeColumn.cellValueFactory = PropertyValueFactory("type")
        fuelTypeColumn.cellValueFactory = PropertyValueFactory("fuelType")
        userIdColumn.cellValueFactory = PropertyValueFactory("userId")
    }

    private fun initialMapAndTableLoad() {
        println("MainController: initialMapAndTableLoad called.")
        if (!::apiClient.isInitialized) {
            println("MainController: initialMapAndTableLoad - apiClient not yet initialized.")
            mapVisualizationManager.redrawAll() // Обновить карту с плейсхолдером
            updateTableWithVehicles(emptyList()) // Очистить таблицу
            return
        }
        if (apiClient.isConnected() && apiClient.getCurrentUserCredentials() != null) {
            println("MainController: initialMapAndTableLoad - Connected and user logged in. Fetching data.")
            fetchAndDisplayMapObjects(animate = !mapDataLoadedAtLeastOnce)
            refreshVehicleTableData()
        } else {
            println("MainController: initialMapAndTableLoad - Not connected or user not logged in.")
            mapVisualizationManager.redrawAll() // Обновить карту с плейсхолдером
            updateTableWithVehicles(emptyList()) // Очистить таблицу
        }
    }

    fun setApiClient(apiClient: ApiClient) {
        println("MainController: setApiClient called.")
        this.apiClient = apiClient
        setupApiClientListeners()
        apiClient.getCachedCommandDescriptors()?.let { updateCommandRegistryAndDisplay(it) }
        refreshUIState()
    }

    fun setMainApp(mainApp: MainApp) { this.mainApp = mainApp }
    fun setCurrentStage(stage: Stage) { this.currentStage = stage }

    fun userLoggedIn() {
        println("MainController: userLoggedIn() signal received.")
        refreshUIState()
        if (apiClient.isConnected()) {
            fetchAndDisplayMapObjects(animate = !mapDataLoadedAtLeastOnce)
            refreshVehicleTableData()
        }
    }

    private fun refreshUIState() {
        if (!::apiClient.isInitialized) { return }
        Platform.runLater {
            println("MainController: Refreshing UI State. Connected: ${apiClient.isConnected()}, User: ${apiClient.getCurrentUserCredentials()?.first}, Commands: ${commandRegistry.size}")
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
                updateCommandRegistryAndDisplay(descriptorsFromServer)
            }
        }

        apiClient.onConnectionStatusChanged = { isConnected, message ->
            Platform.runLater {
                refreshUIState() // Обновляем общий UI (статус, кнопки команд, имя пользователя)

                if (isConnected) {
                    val currentCreds = apiClient.getCurrentUserCredentials()
                    if (currentCreds != null) {
                        println("MainController: Connection (re-)established for ${currentCreds.first}. Refreshing data.")
                        fetchAndDisplayMapObjects(animate = !mapDataLoadedAtLeastOnce)
                        refreshVehicleTableData()
                    } else {
                        println("MainController: Connection established, but user not logged in. Clearing data displays.")
                        mapVisualizationManager.replaceAllVehicles(emptyList())
                        updateTableWithVehicles(emptyList())
                        mapDataLoadedAtLeastOnce = false
                    }
                } else {
                    println("MainController: Connection lost.")
                    // UI уже обновлен через refreshUIState до "Disconnected".
                    // Данные на карте и в таблице остаются, но новые команды не выполнить.
                    // Можно явно очистить, если такое поведение предпочтительнее:
                    // mapVisualizationManager.replaceAllVehicles(emptyList())
                    // updateTableWithVehicles(emptyList())
                    // mapDataLoadedAtLeastOnce = false
                }
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

                    if (effectiveAnimate) {
                        mapVisualizationManager.replaceAllVehicles(emptyList()) // Очищаем перед анимацией
                        vehiclesFromServer.forEach { mapVisualizationManager.addVehicleAnimated(it) }
                        if (vehiclesFromServer.isNotEmpty()) mapDataLoadedAtLeastOnce = true
                    } else {
                        mapVisualizationManager.replaceAllVehicles(vehiclesFromServer)
                        // Устанавливаем флаг, если это была первая загрузка, даже неанимированная
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



    private fun updateTableWithVehicles(vehicles: List<Vehicle>) {
        vehicleData.setAll(vehicles) // setAll лучше для ObservableList, чем clear + addAll
        println("MainController: TableView updated with ${vehicles.size} items.")
    }

    @FXML
    private fun handleLogout() {
        println("Logout button clicked by ${apiClient.getCurrentUserCredentials()?.first ?: "Guest"}")
        apiClient.clearCurrentUserCredentials()
        this.commandRegistry.clear()
        mapVisualizationManager.replaceAllVehicles(emptyList())
        vehicleData.clear()
        mapDataLoadedAtLeastOnce = false
        refreshUIState() // Обновит UI (покажет "Not logged in" и т.д.)
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
        var collectedArgs: List<String>? = null // Будет содержать введенные аргументы или null

        // Проверяем, нужно ли запрашивать аргументы
        val argumentsToAskFor = descriptor.arguments.filter {
            it.type != common.ArgumentType.NO_ARGS && !it.isOptional // Только обязательные не NO_ARGS аргументы
        }
        // Можно добавить логику для опциональных, если очень нужно, но пока упростим

        if (argumentsToAskFor.isNotEmpty()) {
            collectedArgs = showArgumentInputDialog(descriptor.name, argumentsToAskFor)
            if (collectedArgs == null) { // Пользователь отменил ввод или произошла ошибка валидации
                showInfoAlert("Cancelled", "Command '${descriptor.name}' execution cancelled.")
                return
            }
        } else {
            // Либо нет аргументов, либо все они NO_ARGS, либо все опциональные (и мы их не спрашиваем)
            collectedArgs = emptyList() // Команда не требует явного ввода аргументов
            println("Command '${descriptor.name}' does not require mandatory argument input.")
        }

        var vehicleForRequest: Vehicle? = null
        if (descriptor.requiresVehicleObject) {
            // TODO: Это следующий шаг - показать VehicleInputDialog
            // Пока заглушка:
            val vehicleDialog = VehicleInputDialog(currentStage, null) // null для нового
            val returnedVehicle = vehicleDialog.showAndWaitWithResult()
            if (returnedVehicle != null) {
                vehicleForRequest = returnedVehicle
            } else {
                showInfoAlert("Cancelled", "Vehicle input cancelled for command '${descriptor.name}'.")
                return
            }
            // showErrorAlert("Vehicle Input", "Command '${descriptor.name}' requires Vehicle data. GUI for this is a TODO.")
            // return
        }

        val request = Request(
            body = listOf(descriptor.name) + (collectedArgs ?: emptyList()), // Добавляем собранные аргументы
            vehicle = vehicleForRequest,
            username = currentCreds.first,
            password = currentCreds.second
        )

        showInfoAlert("Processing", "Sending command '${descriptor.name}' to server...")
        Thread {
            val response = apiClient.sendRequestAndWaitForResponse(request)
            Platform.runLater {
                if (response != null) {
                    showInfoAlert("Server Response - ${descriptor.name}", response.responseText) // Показываем текстовый ответ

                    if (response.responseText.contains("Authentication failed", ignoreCase = true)) {
                        apiClient.clearCurrentUserCredentials()
                        // Не вызываем refreshUIState() напрямую, onLogout должен вызвать его или метод, который вызовет
                        mainApp.onLogout(currentStage)
                    } else if (!response.responseText.lowercase().startsWith("error")) {
                        // --- НАЧАЛО ВСТАВКИ для handleCommandExecution ---
                        val commandsThatModifyData = setOf(
                            "add", "add_if_max", "update", "remove_by_id", "clear",
                            "remove_greater", "remove_lower", "execute_script"
                            // Добавьте сюда все ваши команды, изменяющие коллекцию, в НИЖНЕМ РЕГИСТРЕ
                        )

                        val commandNameLower = descriptor.name.lowercase()

                        if (commandsThatModifyData.contains(commandNameLower)) {
                            println("Command ${descriptor.name} might have changed data. Refreshing table by calling 'show'.")
                            refreshVehicleTableData() // Вызываем 'show' для обновления
                        } else if (commandNameLower == "show") {
                            if (response.vehicles != null) {
                                println("Command 'show' executed. Updating table directly from response.")
                                updateTableWithVehicles(response.vehicles)
                            } else {
                                println("Command 'show' executed, but no vehicle data in response. Text: ${response.responseText}")
                                if (response.responseText.contains("Collection is empty", ignoreCase = true)) {
                                    updateTableWithVehicles(emptyList()) // Явно очищаем, если коллекция пуста
                                }
                                // Иначе, возможно, ничего не делаем или показываем ошибку, если ожидались данные
                            }
                        }
                    }
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
    private fun showVehicleInfo(clickedVehicle: Vehicle) {

    }

    private fun showDialogValidationError(message: String, ownerDialog: Dialog<*>) {
        Alert(Alert.AlertType.ERROR).apply {
            initOwner(ownerDialog.dialogPane.scene.window) // Привязываем Alert к диалогу
            title = "Validation Error"
            headerText = "Invalid input"
            contentText = message
        }.showAndWait()
    }

    private fun showArgumentInputDialog(commandName: String, argumentsToAskFor: List<common.CommandArgument>): List<String>? {
        // Если список аргументов для запроса пуст (хотя мы уже проверили это перед вызовом),
        // на всякий случай возвращаем пустой список.
        if (argumentsToAskFor.isEmpty()) {
            return emptyList()
        }

        val dialog = Dialog<List<String>>()
        dialog.title = "Input for $commandName"
        dialog.headerText = "Please enter arguments for command: $commandName"

        val okButtonType = ButtonType("OK", ButtonData.OK_DONE)
        dialog.dialogPane.buttonTypes.addAll(okButtonType, ButtonType.CANCEL)

        val grid = GridPane().apply {
            hgap = 10.0
            vgap = 10.0
            padding = Insets(20.0, 20.0, 10.0, 20.0) // Настройте отступы
        }

        val inputFields = mutableListOf<TextField>()

        argumentsToAskFor.forEachIndexed { index, argDesc ->
            // Метка: Имя аргумента (тип)
            grid.add(Label("${argDesc.name} (${argDesc.type.name.lowercase()}):"), 0, index)
            val textField = TextField().apply {
                promptText = argDesc.description ?: argDesc.name
            }
            grid.add(textField, 1, index)
            inputFields.add(textField)
        }

        dialog.dialogPane.content = grid

        // Фокус на первое поле ввода
        Platform.runLater { inputFields.firstOrNull()?.requestFocus() }

        dialog.setResultConverter { dialogButton ->
            if (dialogButton == okButtonType) {
                val enteredValues = mutableListOf<String>()
                for ((i, textField) in inputFields.withIndex()) {
                    val argDesc = argumentsToAskFor[i] // Соответствующий дескриптор аргумента
                    val value = textField.text.trim()

                    // 1. Проверка на пустоту для обязательных (все в argumentsToAskFor - обязательные)
                    if (value.isEmpty()) {
                        // Эта проверка не нужна, так как argumentsToAskFor уже содержит только !isOptional.
                        // if (!argDesc.isOptional) { ... }
                        showDialogValidationError("Argument '${argDesc.name}' is required and cannot be empty.", dialog)
                        return@setResultConverter null // Остаемся в диалоге
                    }

                    // 2. Валидация типа
                    when (argDesc.type) {
                        common.ArgumentType.INTEGER -> {
                            try {
                                value.toInt() // Проверяем, что можем преобразовать
                            } catch (e: NumberFormatException) {
                                showDialogValidationError(
                                    "Argument '${argDesc.name}' must be a valid integer. You entered: '$value'",
                                    dialog
                                )
                                return@setResultConverter null
                            }
                        }
                        common.ArgumentType.DOUBLE -> {
                            try {
                                value.toDouble()
                            } catch (e: NumberFormatException) {
                                showDialogValidationError(
                                    "Argument '${argDesc.name}' must be a valid number (double). You entered: '$value'",
                                    dialog
                                )
                                return@setResultConverter null
                            }
                        }
                        common.ArgumentType.STRING -> {
                            // Для строки особой валидации типа нет, только на пустоту (уже проверено)
                        }
                        common.ArgumentType.NO_ARGS -> { /* Сюда не должны попадать, т.к. отфильтровали */ }
                    }
                    enteredValues.add(value) // Добавляем валидное значение (как строку)
                }
                return@setResultConverter enteredValues
            }
            null // Для кнопки Cancel или закрытия окна
        }

        return dialog.showAndWait().orElse(null)
    }

    private fun refreshVehicleTableData() {
        val currentUserCreds = apiClient.getCurrentUserCredentials()
        if (currentUserCreds == null || !apiClient.isConnected()) {
            println("Cannot refresh table: Not logged in or not connected.")
            Platform.runLater { vehicleData.clear() }
            return
        }
        println("MainController: Requesting vehicle data for table...")
        val showRequest = Request(
            body = listOf("show"), username = currentUserCreds.first, password = currentUserCreds.second
        )
        Thread {
            val response = apiClient.sendRequestAndWaitForResponse(showRequest)
            Platform.runLater {
                if (response?.vehicles != null && !response.responseText.lowercase().contains("error:")) {
                    updateTableWithVehicles(response.vehicles)
                } else if (response != null && response.responseText.contains("Collection is empty", ignoreCase = true)) {
                    updateTableWithVehicles(emptyList())
                } else {
                    showErrorAlert("Table Update Error", "Failed to retrieve vehicle data. Response: ${response?.responseText}")
                    vehicleData.clear()
                }
            }
        }.start()
    }

    private fun updateTableWithVehicles(vehicles: List<Vehicle>?) {
        vehicleData.clear()
        if (vehicles != null) {
            vehicleData.addAll(vehicles)
            println("MainController: TableView updated with ${vehicles.size} items.")
        } else {
            println("MainController: No vehicles to update table with.")
        }
    }
}