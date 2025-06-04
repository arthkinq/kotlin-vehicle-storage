package gui

import app.MainApp
import common.CommandDescriptor
import common.Request
import core.ApiClient
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.fxml.FXML
import javafx.scene.control.*
import javafx.scene.control.cell.PropertyValueFactory
import javafx.scene.layout.Pane
import javafx.scene.layout.VBox
import javafx.scene.text.Font
import javafx.stage.FileChooser // Для будущего execute_script
import javafx.stage.Stage
import model.FuelType
import model.Vehicle // Для handleCommandExecution
import model.VehicleType

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
    private var mapDataLoadedAtLeastOnce = false
    private var vehiclesOnMap = listOf<Vehicle>()
    private val commandRegistry = mutableMapOf<String, CommandDescriptor>()
    private val vehicleData: ObservableList<Vehicle> = FXCollections.observableArrayList()
    fun initialize() {
        println("MainController: initialize() called.")
        commandsVBox.children.clear()
        logoutButton.isDisable = true
        // Не вызываем refreshUIState() здесь, так как apiClient еще не установлен.
        // Он будет вызван в конце setApiClient().
        idColumn.cellValueFactory = PropertyValueFactory("id")
        nameColumn.cellValueFactory = PropertyValueFactory("name")
        coordXColumn.cellValueFactory = PropertyValueFactory("coordinateX")
        coordYColumn.cellValueFactory = PropertyValueFactory("coordinateY")
        creationDateColumn.cellValueFactory = PropertyValueFactory("creationDate")
        enginePowerColumn.cellValueFactory = PropertyValueFactory("enginePower")
        distanceColumn.cellValueFactory = PropertyValueFactory("distanceTravelled")
        typeColumn.cellValueFactory = PropertyValueFactory("type")
        fuelTypeColumn.cellValueFactory = PropertyValueFactory("fuelType")
        userIdColumn.cellValueFactory = PropertyValueFactory("userId")
        vehicleTableView.items = vehicleData

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
            println("MainController: Got ${cachedDescriptors.size} cached descriptors from ApiClient.")
            commandRegistry.clear()
            cachedDescriptors.forEach { commandRegistry[it.name.lowercase()] = it }
            updateCommandRegistryAndDisplay(cachedDescriptors)
        }
        refreshUserAndConnectionStatus() // Обновляем UI на основе текущего состояния (включая, возможно, кэшированные команды)
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

            if (creds != null) {
                currentUserLabel.text = "User: ${creds.first}"
                logoutButton.isDisable = false
                // Если мы подключены, залогинены, и таблица данных пуста (например, после реконнекта)
                // то пробуем загрузить данные для таблицы.
                if (apiClient.isConnected() && vehicleData.isEmpty()) { // vehicleData - это ваш ObservableList<Vehicle>
                    println("MainController: refreshUIState - Connected, user logged in, and table data is empty. Attempting to refresh table data.")
                    refreshVehicleTableData() // Запрашиваем данные для таблицы
                }
            } else {
                currentUserLabel.text = "User: Not logged in"
                logoutButton.isDisable = true
                vehicleData.clear()
            }

            connectionStatusLabel.text = if (apiClient.isConnected()) "Connection: Connected" else "Connection: Disconnected"
            if (!apiClient.isConnected() && creds == null) { // Дополнительная проверка
                vehicleData.clear()
            }
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
                updateCommandDisplayItself() // Перерисовываем кнопки с новым реестром
                val creds = apiClient.getCurrentUserCredentials()
                if (creds != null && apiClient.isConnected() && vehicleData.isEmpty() && commandRegistry.isNotEmpty()) {
                    println("MainController: Commands received, user logged in, table empty. Refreshing table data.")
                    refreshVehicleTableData()
                }
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
            // TODO: Это следующий шаг - показать VehicleInputDialog
            // Пока заглушка:
            val vehicleDialog = VehicleInputDialog(currentStage, null) // null для нового
            val returnedVehicle = vehicleDialog.showAndWaitWithResult()
            if (returnedVehicle != null) {
                vehicleForRequest = returnedVehicle
            } else {showInfoAlert(
                "Cancelled", "Vehicle input cancelled for command '${descriptor.name}'.")
                return
            }
            // showErrorAlert("Vehicle Input",
                "Command '${descriptor.name}' requires Vehicle data. GUI for this is a TODO.")
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
                    showInfoAlert("Server Response - ${descriptor.name}", response.responseText)
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
                    // TODO: Обновить TableView/Visualization, если команда изменяла данные
                } else {
                    showErrorAlert("Server Error", "No response or timeout for command '${descriptor.name}'.")
                }
            }
        }.start()
    }
    private fun showDialogValidationError(message: String, ownerDialog: Dialog<*>) {
        Alert(Alert.AlertType.ERROR).apply {
            initOwner(ownerDialog.dialogPane.scene.window) // Привязываем Alert к диалогу
            title = "Validation Error"
            headerText = "Invalid input"
            contentText = message
        }.showAndWait()
    }

    private fun showInfoAlert(title: String, content: String) {
        Alert(Alert.AlertType.INFORMATION).apply {
            this.title = title; this.headerText = null; this.contentText = content; this.showAndWait()
        }
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
    private fun showErrorAlert(title: String, content: String) {
        Alert(Alert.AlertType.ERROR).apply {
            this.title = title; this.headerText = null; this.contentText = content; this.showAndWait()
        }
    }
    private fun refreshVehicleTableData() {
        val currentCreds = apiClient.getCurrentUserCredentials()
        if (currentCreds == null || !apiClient.isConnected()) {
            println("Cannot refresh table: Not logged in or not connected.")
            vehicleData.clear() // Очищаем таблицу, если нет данных или нет соединения/логина
            return
        }

        println("MainController: Requesting vehicle data from server (using 'show' command)...")
        val showRequest = Request(
            body = listOf("show"), // Имя вашей команды для получения всех объектов
            username = currentCreds.first,
            password = currentCreds.second
        )

        Thread {
            val response = apiClient.sendRequestAndWaitForResponse(showRequest)
            Platform.runLater {
                if (response?.vehicles != null) {
                    println("MainController: Received ${response.vehicles.size} vehicles from server.")
                    updateTableWithVehicles(response.vehicles)
                } else {
                    showErrorAlert("Table Update Error", "Failed to retrieve vehicle data from server. Response: ${response?.responseText}")
                    // Можно очистить таблицу или оставить старые данные, в зависимости от предпочтений
                    // vehicleData.clear()
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