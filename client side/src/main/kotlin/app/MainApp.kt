package app

import core.ApiClient
import gui.LoginController
import gui.MainController
import javafx.application.Application
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Alert
import javafx.stage.Stage
import myio.ConsoleOutputManager
import myio.IOManager
import myio.InputManager

class MainApp : Application() {

    private lateinit var apiClient: ApiClient
    private lateinit var ioManager: IOManager

    @Volatile
    private var mainWindowIsActive = false

    override fun init() {
        super.init()
        ioManager = IOManager(
            input = object : InputManager {
                override fun readLine(): String {
                    System.err.println("WARNING: ApiClient.readLine() called in GUI mode!")
                    return ""
                }
                override fun hasInput(): Boolean = false
            },
            output = ConsoleOutputManager()
        )
        apiClient = ApiClient(ioManager, serverHost = "localhost", serverPort = 8888)
    }

    override fun start(primaryStage: Stage) {
        try {
            showLoginScreen(primaryStage)
        } catch (e: Exception) {
            handleFatalError("Failed during application startup", e)
        }
    }

    private fun showLoginScreen(stage: Stage) {
        mainWindowIsActive = false
        stage.title = "Login - Transport Manager"
        try {
            val loader = FXMLLoader(javaClass.getResource("/gui/LoginView.fxml"))
            val root: Parent = loader.load()

            val loginController = loader.getController<LoginController>()
            loginController.setApiClient(apiClient)
            loginController.setMainApp(this)
            loginController.setCurrentStage(stage)

            val scene = Scene(root)
            stage.scene = scene
            stage.isResizable = false
            stage.minWidth = 700.0
            stage.minHeight = 400.0
            stage.width = 700.0
            stage.height = 500.0
            stage.centerOnScreen()

            stage.setOnCloseRequest {
                if (!mainWindowIsActive) {
                    ioManager.outputLine("Login window closed by user. Exiting application.")
                    Platform.exit()
                }
            }
            if (!stage.isShowing) {
                stage.show()
            }
        } catch (e: Exception) {
            handleFatalError("Failed to load LoginView.fxml", e)
        }
    }

    fun onLoginSuccess(loginStage: Stage, username: String) {
        ioManager.outputLine("Login successful for $username! Proceeding to main application window.")
        mainWindowIsActive = true
        showMainWindow(loginStage, username)
    }

    private fun showMainWindow(currentStage: Stage, loggedInUsername: String) {
        currentStage.title = "Transport Manager - Main"
        try {
            val loader = FXMLLoader(javaClass.getResource("/gui/MainView.fxml"))
            val root: Parent = loader.load()

            val mainController = loader.getController<MainController>()
            mainController.setApiClient(apiClient)
            mainController.setMainApp(this)
            mainController.setCurrentStage(currentStage)
            mainController.userLoggedIn()

            val scene = Scene(root, 1200.0, 708.0)
            currentStage.scene = scene
            currentStage.minWidth = 1500.0
            currentStage.minHeight = 708.0
            currentStage.centerOnScreen()

            currentStage.setOnCloseRequest {
                ioManager.outputLine("Main window close request. Exiting application.")
                Platform.exit()
            }
            ioManager.outputLine("Main window shown for user $loggedInUsername.")
        } catch (e: Exception) {
            handleFatalError("Failed to load or show MainView.fxml", e)
        }
    }

    fun onLogout(mainStage: Stage) {
        ioManager.outputLine("User logged out. Returning to login screen.")
        showLoginScreen(mainStage)
    }

    override fun stop() {
        super.stop()
        ioManager.outputLine("Application stopping...")
        if (::apiClient.isInitialized && apiClient.isRunning()) {
            apiClient.close()
            ioManager.outputLine("ApiClient stopped from MainApp.stop().")
        } else if (::apiClient.isInitialized) {
            ioManager.outputLine("ApiClient was initialized but not running (or already closed).")
        } else {
            ioManager.outputLine("ApiClient was not initialized.")
        }
        ioManager.outputLine("Application stopped.")
    }

    private fun handleFatalError(message: String, throwable: Throwable? = null) {
        val fullMessage = if (throwable != null) "$message: ${throwable.message}" else message
        ioManager.error(fullMessage)
        throwable?.printStackTrace(System.err)
        if (Platform.isFxApplicationThread()) {
             Alert(Alert.AlertType.ERROR, fullMessage).showAndWait()
        }
        Platform.exit()
    }
}
fun main() {
    Application.launch(MainApp::class.java)
}