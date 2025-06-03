package app

import core.ApiClient
import gui.LoginController
import gui.MainController
import javafx.application.Application
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.stage.Stage
import myio.ConsoleInputManager
import myio.ConsoleOutputManager
import myio.IOManager

class MainApp : Application() {

    private lateinit var apiClient: ApiClient
    private lateinit var ioManager: IOManager

    @Volatile
    private var mainWindowShown = false

    override fun init() {
        super.init()
        ioManager = IOManager(
            input = ConsoleInputManager(),
            output = ConsoleOutputManager()
        )
        apiClient = ApiClient(ioManager, serverHost = "localhost", serverPort = 8888)
    }

    override fun start(primaryStage: Stage) {
        primaryStage.title = "Login - Transport Manager"
        try {
            val loader = FXMLLoader(javaClass.getResource("/gui/LoginView.fxml"))
            val root: Parent = loader.load()

            val loginController = loader.getController<LoginController>()
            loginController.setApiClient(apiClient)
            loginController.setMainApp(this)
            loginController.setCurrentStage(primaryStage)

            val scene = Scene(root)
            primaryStage.scene = scene
            primaryStage.isResizable = false
            primaryStage.centerOnScreen()

            primaryStage.setOnCloseRequest {
                if (!mainWindowShown) {
                    ioManager.outputLine("Login cancelled or window closed. Exiting application.")
                    Platform.exit()
                }
            }

            primaryStage.show()

        } catch (e: Exception) {
            e.printStackTrace()
            val errorMsg = "Failed to load LoginView.fxml or unexpected error during startup: ${e.message}"
            ioManager.error(errorMsg)
            Platform.exit()
        }
    }

    fun onLoginSuccess(loginStage: Stage, username: String) {
        ioManager.outputLine("Login successful for $username! Proceeding...")
        mainWindowShown = true
        showMainWindow(loginStage, username)
    }


    private fun showMainWindow(currentStage: Stage, loggedInUsername: String) {
        currentStage.title = "Vehicle Manager - Main"
        try {
            val loader = FXMLLoader(javaClass.getResource("/gui/MainView.fxml"))
            val root: Parent = loader.load()

            val mainController = loader.getController<MainController>()
            mainController.setApiClient(apiClient)
            mainController.setMainApp(this)
            mainController.setCurrentStage(currentStage)
            mainController.setLoggedInUser(loggedInUsername)

            val scene = Scene(root)
            currentStage.scene = scene
            currentStage.isResizable = true
            currentStage.minWidth = 1200.0
            currentStage.minHeight = 200.0
            currentStage.centerOnScreen()

            currentStage.setOnCloseRequest {
                ioManager.outputLine("Main window close request from MainApp.")
                Platform.exit()
            }
            ioManager.outputLine("Main window shown for user $loggedInUsername.")
        } catch (e: Exception) {
            e.printStackTrace()
            val errorMsg = "Failed to load or show app.MainView.fxml: ${e.message}"
            ioManager.error(errorMsg)
            // Alert(Alert.AlertType.ERROR, errorMsg).showAndWait()
            mainWindowShown = false
            Platform.exit()
        }
    }

    override fun stop() {
        super.stop()
        if (::apiClient.isInitialized && apiClient.isRunning()) {
            apiClient.close()
            ioManager.outputLine("ApiClient stopped from MainApp.stop().")
        }
        ioManager.outputLine("Application stopped.")
    }

    // onLogout и showLoginWindowAgain остаются как в предыдущем ответе
    fun onLogout(mainStage: Stage) {
        ioManager.outputLine("User logged out. Returning to login screen.")
        mainWindowShown = false
        // Показываем окно логина на том же Stage
        try {
            val loader = FXMLLoader(javaClass.getResource("/gui/LoginView.fxml"))
            val root: Parent = loader.load()
            val loginController = loader.getController<LoginController>()
            loginController.setApiClient(apiClient)
            loginController.setMainApp(this)
            loginController.setCurrentStage(mainStage)

            val scene = Scene(root)
            mainStage.title = "Login - Transport Manager"
            mainStage.scene = scene
        } catch (e: Exception) {
            e.printStackTrace()
            ioManager.error("Failed to re-load LoginView.fxml for logout: ${e.message}")
            Platform.exit()
        }
    }
}

fun main() {
    Application.launch(MainApp::class.java)
}