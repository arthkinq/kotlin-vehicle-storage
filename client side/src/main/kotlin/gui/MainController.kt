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
import javafx.scene.text.Text
import javafx.stage.Stage
import javafx.util.Callback
import model.FuelType
import model.Vehicle
import model.VehicleType
import util.LocaleManager
import java.util.Locale
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class MainController {

    @FXML private lateinit var appTitleTextMain: Text
    @FXML private lateinit var languageComboBoxMain: ComboBox<Locale>
    @FXML private lateinit var mainTabPane: TabPane
    @FXML private lateinit var mapTab: Tab
    @FXML private lateinit var tableTab: Tab
    @FXML private lateinit var commandsTitleText: Text

    @FXML private lateinit var mapPane: Pane
    @FXML private lateinit var mapCanvas: Canvas
    @FXML private lateinit var commandsVBox: VBox
    @FXML private lateinit var currentUserLabel: Label
    @FXML private lateinit var connectionStatusLabel: Label
    @FXML private lateinit var logoutButton: Button

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
        if (::commandsVBox.isInitialized) commandsVBox.children.clear()
        if (::logoutButton.isInitialized) logoutButton.isDisable = true

        if (::vehicleTableView.isInitialized) {
            setupTableColumns()
            vehicleTableView.items = vehicleData
        } else {
            println("WARN: MainController - vehicleTableView not initialized!")
        }

        if (::languageComboBoxMain.isInitialized) {
            languageComboBoxMain.items.addAll(LocaleManager.supportedLocales)
            languageComboBoxMain.value = LocaleManager.currentLocale
            languageComboBoxMain.setCellFactory { LanguageListCell() }
            languageComboBoxMain.buttonCell = LanguageListCell()
            languageComboBoxMain.valueProperty().addListener { _, _, newLocale ->
                if (newLocale != null && newLocale != LocaleManager.currentLocale) {
                    LocaleManager.currentLocale = newLocale
                }
            }
        } else {
            println("WARN: MainController - languageComboBoxMain not initialized! Check FXML for fx:id=\"languageComboBoxMain\".")
        }

        LocaleManager.currentLocaleProperty.addListener { _, _, newLocale ->
            println("MainController: Locale changed to $newLocale, updating UI.")
            updateLocalizedTexts()
            if (::vehicleTableView.isInitialized) {
                setupLocalizedTableRenderers()
                vehicleTableView.refresh()
            }
        }
        updateLocalizedTexts()
        if (::vehicleTableView.isInitialized) setupLocalizedTableRenderers()


        Platform.runLater {
            if (::mapCanvas.isInitialized && ::mapPane.isInitialized) {
                mapVisualizationManager = MapVisualizationManager(mapCanvas) { clickedVehicle ->
                    showVehicleInfo(clickedVehicle)
                }
                mapCanvas.widthProperty().bind(mapPane.widthProperty())
                mapCanvas.heightProperty().bind(mapPane.heightProperty())
                mapCanvas.widthProperty().addListener { _ -> if(::mapVisualizationManager.isInitialized) mapVisualizationManager.redrawAll() }
                mapCanvas.heightProperty().addListener { _ -> if(::mapVisualizationManager.isInitialized) mapVisualizationManager.redrawAll() }

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
                println("MainController: ERROR - mapCanvas or mapPane was not initialized by FXML loader!")
            }
        }
    }

    private fun updateLocalizedTexts() {
        println("MainController: updateLocalizedTexts() for locale ${LocaleManager.currentLocale}")
        if (::currentStage.isInitialized) {
            currentStage.titleProperty().unbind()
            currentStage.title = LocaleManager.getString("main.appTitle")
        }

        if (::appTitleTextMain.isInitialized) appTitleTextMain.textProperty().bind(LocaleManager.getObservableString("main.appTitle"))
        if (::logoutButton.isInitialized) logoutButton.textProperty().bind(LocaleManager.getObservableString("main.button.logout"))

        if (::mapTab.isInitialized) mapTab.textProperty().bind(LocaleManager.getObservableString("main.tab.map"))
        if (::tableTab.isInitialized) tableTab.textProperty().bind(LocaleManager.getObservableString("main.tab.table"))
        if (::commandsTitleText.isInitialized) commandsTitleText.textProperty().bind(LocaleManager.getObservableString("main.text.commands"))

        if (::idColumn.isInitialized) idColumn.textProperty().bind(LocaleManager.getObservableString("column.id"))
        if (::nameColumn.isInitialized) nameColumn.textProperty().bind(LocaleManager.getObservableString("column.name"))
        if (::coordXColumn.isInitialized) coordXColumn.textProperty().bind(LocaleManager.getObservableString("column.coordX"))
        if (::coordYColumn.isInitialized) coordYColumn.textProperty().bind(LocaleManager.getObservableString("column.coordY"))
        if (::creationDateColumn.isInitialized) creationDateColumn.textProperty().bind(LocaleManager.getObservableString("column.creationDate"))
        if (::enginePowerColumn.isInitialized) enginePowerColumn.textProperty().bind(LocaleManager.getObservableString("column.enginePower"))
        if (::distanceColumn.isInitialized) distanceColumn.textProperty().bind(LocaleManager.getObservableString("column.distance"))
        if (::typeColumn.isInitialized) typeColumn.textProperty().bind(LocaleManager.getObservableString("column.type"))
        if (::fuelTypeColumn.isInitialized) fuelTypeColumn.textProperty().bind(LocaleManager.getObservableString("column.fuelType"))
        if (::userIdColumn.isInitialized) userIdColumn.textProperty().bind(LocaleManager.getObservableString("column.userId"))

        updateCommandDisplayItself()
        refreshUIState()
    }

    private fun setupTableColumns() {
        if (!::vehicleTableView.isInitialized) return
        if (::idColumn.isInitialized) idColumn.cellValueFactory = PropertyValueFactory("id")
        if (::nameColumn.isInitialized) nameColumn.cellValueFactory = PropertyValueFactory("name")
        if (::coordXColumn.isInitialized) coordXColumn.setCellValueFactory { cellData -> javafx.beans.property.SimpleIntegerProperty(cellData.value.coordinates.x).asObject() }
        if (::coordYColumn.isInitialized) coordYColumn.setCellValueFactory { cellData -> javafx.beans.property.SimpleFloatProperty(cellData.value.coordinates.y).asObject() }
        if (::creationDateColumn.isInitialized) creationDateColumn.cellValueFactory = PropertyValueFactory("creationDate")
        if (::enginePowerColumn.isInitialized) enginePowerColumn.cellValueFactory = PropertyValueFactory("enginePower")
        if (::distanceColumn.isInitialized) distanceColumn.cellValueFactory = PropertyValueFactory("distanceTravelled")
        if (::typeColumn.isInitialized) typeColumn.cellValueFactory = PropertyValueFactory("type")
        if (::fuelTypeColumn.isInitialized) fuelTypeColumn.cellValueFactory = PropertyValueFactory("fuelType")
        if (::userIdColumn.isInitialized) userIdColumn.cellValueFactory = PropertyValueFactory("userId")
    }

    private fun setupLocalizedTableRenderers() {
        if (!::vehicleTableView.isInitialized) return
        println("MainController: setupLocalizedTableRenderers() for locale ${LocaleManager.currentLocale}")

        val numberCellFactoryProvider: (Int) -> Callback<TableColumn<Vehicle, Number?>, TableCell<Vehicle, Number?>> = { maxFractionDigits ->
            Callback { _ ->
                object : TableCell<Vehicle, Number?>() {
                    override fun updateItem(item: Number?, empty: Boolean) {
                        super.updateItem(item, empty)
                        text = if (empty || item == null) null else {
                            try {
                                val nf = NumberFormat.getNumberInstance(LocaleManager.currentLocale)
                                nf.maximumFractionDigits = maxFractionDigits
                                nf.isGroupingUsed = true
                                nf.format(item)
                            } catch (e: IllegalArgumentException) {
                                System.err.println("Error formatting number $item for locale ${LocaleManager.currentLocale}: ${e.message}")
                                item.toString()
                            }
                        }
                    }
                }
            }
        }
        if (::idColumn.isInitialized) (idColumn as TableColumn<Vehicle, Number?>).setCellFactory(numberCellFactoryProvider(0))
        if (::coordXColumn.isInitialized) (coordXColumn as TableColumn<Vehicle, Number?>).setCellFactory(numberCellFactoryProvider(0))
        if (::coordYColumn.isInitialized) (coordYColumn as TableColumn<Vehicle, Number?>).setCellFactory(numberCellFactoryProvider(1))
        if (::enginePowerColumn.isInitialized) (enginePowerColumn as TableColumn<Vehicle, Number?>).setCellFactory(numberCellFactoryProvider(2))
        if (::distanceColumn.isInitialized) (distanceColumn as TableColumn<Vehicle, Number?>).setCellFactory(numberCellFactoryProvider(2))
        if (::userIdColumn.isInitialized) (userIdColumn as TableColumn<Vehicle, Number?>).setCellFactory(numberCellFactoryProvider(0))

        if (::creationDateColumn.isInitialized) {
            creationDateColumn.setCellFactory { _ ->
                object : TableCell<Vehicle, Long>() {
                    override fun updateItem(item: Long?, empty: Boolean) {
                        super.updateItem(item, empty)
                        text = if (empty || item == null || item == 0L) null else {
                            try {
                                val instant = Instant.ofEpochMilli(item)
                                DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                                    .withLocale(LocaleManager.currentLocale)
                                    .withZone(ZoneId.systemDefault())
                                    .format(instant)
                            } catch (e: Exception) {
                                System.err.println("Error formatting date $item for locale ${LocaleManager.currentLocale}: ${e.message}")
                                item.toString()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun initialMapAndTableLoad() {
        if (!::apiClient.isInitialized) return
        if (apiClient.isConnected() && apiClient.getCurrentUserCredentials() != null) {
            if (::mapVisualizationManager.isInitialized) fetchAndDisplayMapObjects(animate = !mapDataLoadedAtLeastOnce)
            refreshVehicleTableData()
        }
    }

    fun setApiClient(apiClient: ApiClient) {
        this.apiClient = apiClient
        setupApiClientListeners()
        apiClient.getCachedCommandDescriptors()?.let { updateCommandRegistryAndDisplay(it) }
        refreshUIState()
    }

    fun setMainApp(mainApp: MainApp) { this.mainApp = mainApp }

    fun setCurrentStage(stage: Stage) {
        this.currentStage = stage
        if (::currentStage.isInitialized) {
            currentStage.title = LocaleManager.getString("main.appTitle")
            LocaleManager.currentLocaleProperty.addListener { _, _, _ ->
                if (currentStage.isShowing) {
                    currentStage.title = LocaleManager.getString("main.appTitle")
                }
            }
        }
    }

    fun userLoggedIn() {
        println("MainController: userLoggedIn() signal received.")
        refreshUIState()
        if (::apiClient.isInitialized && apiClient.isConnected()) {
            if (::mapVisualizationManager.isInitialized) fetchAndDisplayMapObjects(animate = !mapDataLoadedAtLeastOnce)
            refreshVehicleTableData()
        }
    }

    private fun refreshUIState() {
        if (!::apiClient.isInitialized) { return }

        Platform.runLater {
            val creds = apiClient.getCurrentUserCredentials()
            val userStatusText = creds?.first ?: LocaleManager.getString("status.notLoggedIn")

            if (::currentUserLabel.isInitialized) {
                currentUserLabel.textProperty().unbind()
                currentUserLabel.text = "${LocaleManager.getString("main.label.currentUser")} $userStatusText"
            }

            if (::logoutButton.isInitialized) logoutButton.isDisable = creds == null

            val connectionTextKey = if (apiClient.isConnected()) "status.connected" else "status.disconnected"
            if (::connectionStatusLabel.isInitialized) {
                connectionStatusLabel.textProperty().unbind()
                connectionStatusLabel.text = "${LocaleManager.getString("main.label.connectionStatus")} ${LocaleManager.getString(connectionTextKey)}"
            }

            updateCommandDisplayItself()
            if (::mapVisualizationManager.isInitialized) mapVisualizationManager.redrawAll()

            if (creds != null && apiClient.isConnected() && vehicleData.isEmpty() && !mapDataLoadedAtLeastOnce) {
                if (::mapVisualizationManager.isInitialized) fetchAndDisplayMapObjects(animate = !mapDataLoadedAtLeastOnce)
                refreshVehicleTableData()
            } else if (creds == null) {
                vehicleData.clear()
                if(::mapVisualizationManager.isInitialized) mapVisualizationManager.replaceAllVehicles(emptyList())
                mapDataLoadedAtLeastOnce = false
            }
        }
    }

    private fun setupApiClientListeners() {
        if (!::apiClient.isInitialized) return
        apiClient.onCommandDescriptorsUpdated = { descriptorsFromServer ->
            Platform.runLater {
                updateCommandRegistryAndDisplay(descriptorsFromServer)
            }
        }
        apiClient.onConnectionStatusChanged = { isConnected, message ->
            Platform.runLater {
                println("MainController: Connection status changed. Connected: $isConnected, Message: $message")
                if (isConnected) {
                    val currentCreds = apiClient.getCurrentUserCredentials()
                    if (currentCreds != null) {
                        if (::mapVisualizationManager.isInitialized) fetchAndDisplayMapObjects(animate = !mapDataLoadedAtLeastOnce)
                        refreshVehicleTableData()
                    } else {
                        if (::mapVisualizationManager.isInitialized) mapVisualizationManager.replaceAllVehicles(emptyList())
                        vehicleData.clear()
                        mapDataLoadedAtLeastOnce = false
                    }
                }
                refreshUIState()
            }
        }
    }

    private fun updateCommandRegistryAndDisplay(descriptors: List<CommandDescriptor>) {
        if (!::apiClient.isInitialized) return
        commandRegistry.clear()
        descriptors.forEach { commandRegistry[it.name.lowercase()] = it }
        println("MainController: commandRegistry updated. New size: ${commandRegistry.size}")
        updateCommandDisplayItself()
    }

    private fun updateCommandDisplayItself() {
        Platform.runLater {
            if (!::commandsVBox.isInitialized || !::apiClient.isInitialized) return@runLater
            commandsVBox.children.clear()
            val currentUser = apiClient.getCurrentUserCredentials()
            val displayableDescriptors = mutableListOf<CommandDescriptor>()

            if (currentUser != null) {
                displayableDescriptors.addAll(this.commandRegistry.values)

                val execScriptName = "execute_script"
                if (displayableDescriptors.none { it.name.equals(execScriptName, ignoreCase = true) }) {
                    displayableDescriptors.add(
                        CommandDescriptor(
                            name = execScriptName,
                            description = LocaleManager.getString("command.execute_script.description",
                                "Execute commands from a script file."),
                            arguments = listOf(common.CommandArgument("filename", ArgumentType.STRING, false,
                                LocaleManager.getString("arg.execute_script.filename.description", "Path to script"))),
                            requiresVehicleObject = false
                        )
                    )
                }
            }

            if (displayableDescriptors.isEmpty()) {
                val placeholderKey = when {
                    !apiClient.isConnected() -> "placeholder.notConnected"
                    currentUser == null && apiClient.isConnected() -> "placeholder.pleaseLogin"
                    currentUser != null && apiClient.isConnected() && commandRegistry.isEmpty() -> "placeholder.loadingCommands"
                    currentUser != null && apiClient.isConnected() -> "placeholder.noCommands"
                    else -> "placeholder.commandsUnavailable"
                }
                commandsVBox.children.add(Label(LocaleManager.getString(placeholderKey)).apply { font = Font.font("Tahoma", 15.0) })
                return@runLater
            }

            displayableDescriptors.sortedBy { it.name }.forEach { desc ->
                val buttonTextKey = "command.${desc.name.lowercase()}.button"
                val buttonText = LocaleManager.getString(buttonTextKey, desc.name.replaceFirstChar { it.titlecase() })
                val button = Button(buttonText)
                button.maxWidth = Double.MAX_VALUE; button.prefHeight = 40.0; button.isWrapText = true
                button.font = Font.font("Tahoma", 14.0)

                val tooltipKey = "command.${desc.name.lowercase()}.description"
                button.tooltip = Tooltip(LocaleManager.getString(tooltipKey, desc.description))

                button.setOnAction { handleCommandExecution(desc) }
                commandsVBox.children.add(button)
            }
        }
    }

    private fun fetchAndDisplayMapObjects(animate: Boolean = false) {
        if (!::apiClient.isInitialized || !::mapVisualizationManager.isInitialized) {
            println("WARN: fetchAndDisplayMapObjects - apiClient or mapVisualizationManager not initialized.")
            return
        }
        val currentUserCreds = apiClient.getCurrentUserCredentials()
        if (currentUserCreds == null || !apiClient.isConnected()) {
            mapVisualizationManager.replaceAllVehicles(emptyList())
            if (currentUserCreds == null) mapDataLoadedAtLeastOnce = false
            println("INFO: fetchAndDisplayMapObjects - User not logged in or not connected. Clearing map.")
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
                if (response?.vehicles != null && !response.responseText.lowercase().contains("error")) {
                    val vehiclesFromServer = response.vehicles
                    println("MainController: Fetched ${vehiclesFromServer.size} vehicles for map.")
                    if (effectiveAnimate) {
                        mapVisualizationManager.replaceAllVehicles(emptyList())
                        vehiclesFromServer.forEach { mapVisualizationManager.addVehicleAnimated(it) }
                    } else {
                        mapVisualizationManager.replaceAllVehicles(vehiclesFromServer)
                    }
                    mapDataLoadedAtLeastOnce = vehiclesFromServer.isNotEmpty()

                } else if (response != null && response.responseText.contains("Collection is empty", ignoreCase = true)) {
                    println("MainController: Server reports collection is empty for map.")
                    mapVisualizationManager.replaceAllVehicles(emptyList())
                    mapDataLoadedAtLeastOnce = false
                } else {
                    val errorDetail = response?.responseText ?: LocaleManager.getString("error.noResponseDetail")
                    showErrorAlert("error.dialogTitle", "error.mapData", errorDetail)
                    mapVisualizationManager.replaceAllVehicles(emptyList())
                }
            }
        }.start()
    }

    @FXML
    private fun handleLogout() {
        if (!::apiClient.isInitialized || !::mainApp.isInitialized) {
            println("WARN: handleLogout - apiClient or mainApp not initialized.")
            return
        }
        val userNameForLog = apiClient.getCurrentUserCredentials()?.first ?: LocaleManager.getString("text.guest")
        println("Logout button clicked by $userNameForLog")

        apiClient.clearCurrentUserCredentials()
        this.commandRegistry.clear()

        if (::mapVisualizationManager.isInitialized) mapVisualizationManager.replaceAllVehicles(emptyList())
        vehicleData.clear()

        mapDataLoadedAtLeastOnce = false
        refreshUIState()
        mainApp.onLogout(currentStage)
    }

    private fun refreshVehicleTableData() {
        if (!::apiClient.isInitialized) {
            println("WARN: refreshVehicleTableData - apiClient not initialized.")
            return
        }
        val currentUserCreds = apiClient.getCurrentUserCredentials()
        if (currentUserCreds == null || !apiClient.isConnected()) {
            println("Cannot refresh table: Not logged in or not connected.")
            vehicleData.clear()
            return
        }

        println("MainController: Requesting vehicle data for table (using 'show' command)...")
        val showRequest = Request(
            body = listOf("show"),
            username = currentUserCreds.first,
            password = currentUserCreds.second
        )

        Thread {
            val response = apiClient.sendRequestAndWaitForResponse(showRequest)
            Platform.runLater {
                if (response?.vehicles != null && !response.responseText.lowercase().contains("error")) {
                    println("MainController: Received ${response.vehicles.size} vehicles for table from server.")
                    updateTableWithVehicles(response.vehicles)
                } else if (response != null && response.responseText.contains("Collection is empty", ignoreCase = true)) {
                    println("MainController: Server reports collection is empty for table.")
                    updateTableWithVehicles(emptyList())
                } else {
                    val errorDetail = response?.responseText ?: LocaleManager.getString("error.noResponseDetail")
                    showErrorAlert("error.dialogTitle", "error.tableUpdate", errorDetail)
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
        if (::currentStage.isInitialized) alert.initOwner(currentStage)
        alert.title = LocaleManager.getString("vehicleInfo.title")
        alert.headerText = LocaleManager.getString("vehicleInfo.header", vehicle.id)

        val nf = NumberFormat.getNumberInstance(LocaleManager.currentLocale)
        val content = StringBuilder()
        content.appendLine("${LocaleManager.getString("vehicleInfo.name")}: ${vehicle.name}")
        content.appendLine("${LocaleManager.getString("vehicleInfo.ownerId")}: ${vehicle.userId}")
        content.appendLine("${LocaleManager.getString("vehicleInfo.coordinates")}: (X: ${nf.format(vehicle.coordinates.x)}, Y: ${nf.format(vehicle.coordinates.y)})")
        content.appendLine("${LocaleManager.getString("vehicleInfo.enginePower")}: ${nf.format(vehicle.enginePower)}")
        content.appendLine("${LocaleManager.getString("vehicleInfo.type")}: ${vehicle.type?.let { LocaleManager.getString("vehicleType.${it.name.lowercase()}", it.name) } ?: LocaleManager.getString("text.notApplicable")}")
        content.appendLine("${LocaleManager.getString("vehicleInfo.fuelType")}: ${vehicle.fuelType?.let { LocaleManager.getString("fuelType.${it.name.lowercase()}", it.name) } ?: LocaleManager.getString("text.notApplicable")}")
        content.appendLine("${LocaleManager.getString("vehicleInfo.distanceTravelled")}: ${vehicle.distanceTravelled?.let { nf.format(it) } ?: LocaleManager.getString("text.notApplicable")}")

        alert.contentText = content.toString()
        alert.showAndWait()
    }

    private fun showInfoAlert(titleKey: String, contentKey: String, vararg args: Any) {
        Platform.runLater {
            Alert(Alert.AlertType.INFORMATION).apply {
                if (::currentStage.isInitialized) initOwner(currentStage)
                this.title = LocaleManager.getString(titleKey)
                this.headerText = null
                this.contentText = LocaleManager.getString(contentKey, *args)
                this.showAndWait()
            }
        }
    }
    private fun showErrorAlert(titleKey: String, contentKey: String, vararg args: Any) {
        Platform.runLater {
            Alert(Alert.AlertType.ERROR).apply {
                if (::currentStage.isInitialized) initOwner(currentStage)
                this.title = LocaleManager.getString(titleKey)
                this.headerText = null
                this.contentText = LocaleManager.getString(contentKey, *args)
                this.showAndWait()
            }
        }
    }
    private fun showInfoAlert(title: String, content: String) {
        showInfoAlert("info.dialogTitle.generic", content, title)
    }
    private fun showErrorAlert(title: String, content: String) {
        showErrorAlert("error.dialogTitle.generic", content, title)
    }


    private fun handleCommandExecution(descriptor: CommandDescriptor) {
        if (!::apiClient.isInitialized || !::mainApp.isInitialized) return

        val currentCreds = apiClient.getCurrentUserCredentials()
        if (currentCreds == null) {
            showErrorAlert("error.dialogTitle", "error.mustBeLoggedIn")
            return
        }
        if (!apiClient.isConnected()) {
            showInfoAlert("info.dialogTitle", "connection.attemptingReconnectForCommand", descriptor.name)
            Thread {
                val connected = apiClient.connectIfNeeded()
                Platform.runLater {
                    if (connected) {
                        showInfoAlert("info.dialogTitle", "connection.reconnectedPleaseRetry", descriptor.name)
                    } else {
                        showErrorAlert("error.dialogTitle", "error.connectFailedForCommand", descriptor.name)
                    }
                }
            }.start()
            return
        }

        var collectedArgs: List<String>? = null
        val argumentsToAskFor = descriptor.arguments.filter { it.type != common.ArgumentType.NO_ARGS && !it.isOptional }
        if (argumentsToAskFor.isNotEmpty()) {
            collectedArgs = showArgumentInputDialog(descriptor.name, argumentsToAskFor)
            if (collectedArgs == null) {
                showInfoAlert("info.dialogTitle", "message.commandCancelledUserArgs", descriptor.name)
                return
            }
        } else {
            collectedArgs = emptyList()
        }

        var vehicleForRequest: Vehicle? = null
        if (descriptor.requiresVehicleObject) {
            val vehicleDialog = VehicleInputDialog(currentStage, null)
            val resultVehicleFromDialog = vehicleDialog.showAndWaitWithResult()

            if (resultVehicleFromDialog != null) {
                vehicleForRequest = resultVehicleFromDialog.copy(
                    userId = 0,
                    creationDate = if (resultVehicleFromDialog.id == 0) System.currentTimeMillis() else resultVehicleFromDialog.creationDate
                )
            } else {
                showInfoAlert("info.dialogTitle", "message.vehicleInputCancelled", descriptor.name)
                return
            }
        }

        val request = Request(
            body = listOf(descriptor.name) + (collectedArgs ?: emptyList()),
            vehicle = vehicleForRequest,
            username = currentCreds.first,
            password = currentCreds.second
        )

        showInfoAlert("info.dialogTitle","message.processingAndSending", descriptor.name)
        Thread {
            val response = apiClient.sendRequestAndWaitForResponse(request)
            Platform.runLater {
                if (response != null) {
                    val responseTitleKey = if (response.responseText.lowercase().startsWith("error")) "error.dialogTitle" else "response.server.title"
                    showInfoAlert(responseTitleKey, response.responseText)

                    if (response.responseText.contains("Authentication failed", ignoreCase = true)) {
                        apiClient.clearCurrentUserCredentials()
                        mainApp.onLogout(currentStage)
                    } else if (!response.responseText.lowercase().startsWith("error")) {
                        val commandsThatModifyData = setOf(
                            "add", "add_if_max", "add_if_min", "update_id", "update",
                            "remove_by_id", "remove_first", "remove_any_by_engine_power", "clear",
                            "remove_greater", "remove_lower", "removebyenginepower"
                        )
                        val commandNameLower = descriptor.name.lowercase()
                        if (commandsThatModifyData.contains(commandNameLower) || commandNameLower == "execute_script") {
                            if (::mapVisualizationManager.isInitialized) fetchAndDisplayMapObjects(animate = false)
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
                    showErrorAlert("error.dialogTitle", "error.noResponseOrTimeout", descriptor.name)
                }
            }
        }.start()
    }

    private fun showArgumentInputDialog(commandName: String, argumentsToAskFor: List<common.CommandArgument>): List<String>? {
        if (argumentsToAskFor.isEmpty()) return emptyList()

        val dialog = Dialog<List<String>>()
        if (::currentStage.isInitialized) dialog.initOwner(currentStage)
        dialog.title = LocaleManager.getString("dialog.inputFor", commandName)
        dialog.headerText = LocaleManager.getString("dialog.header.enterArgsFor", commandName)

        val okButtonType = ButtonType(LocaleManager.getString("button.ok"), ButtonBar.ButtonData.OK_DONE)
        val cancelButtonType = ButtonType(LocaleManager.getString("button.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE)
        dialog.dialogPane.buttonTypes.addAll(okButtonType, cancelButtonType)

        val grid = GridPane().apply {
            hgap = 10.0; vgap = 10.0; padding = Insets(20.0, 150.0, 10.0, 10.0)
        }
        val inputFields = mutableListOf<TextField>()

        argumentsToAskFor.forEachIndexed { index, argDesc ->
            val labelText = LocaleManager.getString("dialog.label.argNameType", argDesc.name, argDesc.type.name.lowercase())
            grid.add(Label(labelText), 0, index)

            val promptKey = "arg.${commandName.lowercase()}.${argDesc.name.lowercase()}.prompt"
            val defaultPrompt = argDesc.description ?: argDesc.name
            val textField = TextField().apply { promptText = LocaleManager.getString(promptKey, defaultPrompt) }

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
                    if (value.isEmpty() && !argDesc.isOptional) {
                        showDialogValidationError(LocaleManager.getString("validation.error.argRequired", argDesc.name), dialog)
                        return@setResultConverter null
                    }
                    if (value.isNotEmpty() || !argDesc.isOptional) {
                        when (argDesc.type) {
                            common.ArgumentType.INTEGER -> try { value.toInt() } catch (e: NumberFormatException) {
                                showDialogValidationError(LocaleManager.getString("validation.error.argMustBeInt", argDesc.name, value), dialog)
                                return@setResultConverter null
                            }
                            common.ArgumentType.DOUBLE -> try { value.toDouble() } catch (e: NumberFormatException) {
                                showDialogValidationError(LocaleManager.getString("validation.error.argMustBeDouble", argDesc.name, value), dialog)
                                return@setResultConverter null
                            }
                            else -> {}
                        }
                    }
                    enteredValues.add(value)
                }
                enteredValues
            } else null
        }
        return dialog.showAndWait().orElse(null)
    }

    private fun showDialogValidationError(message: String, ownerDialog: Dialog<*>) {
        Platform.runLater {
            Alert(Alert.AlertType.ERROR).apply {
                if (ownerDialog.isShowing) initOwner(ownerDialog.dialogPane.scene.window)
                else if (::currentStage.isInitialized) initOwner(currentStage)
                title = LocaleManager.getString("validation.error.title")
                headerText = LocaleManager.getString("validation.error.header")
                contentText = message
            }.showAndWait()
        }
    }
}