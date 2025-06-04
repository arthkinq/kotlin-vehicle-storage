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
    private lateinit var ioManager: IOManager // Для общих логов MainApp и для ApiClient

    @Volatile
    private var mainWindowIsActive = false // Флаг, показывающий, что активно главное окно, а не окно логина

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
        // primaryStage будет использоваться для всех сцен (логин и главное окно)
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
            stage.minWidth = 500.0
            stage.minHeight = 400.0
            stage.width = 500.0
            stage.height = 500.0
            stage.centerOnScreen()

            stage.setOnCloseRequest {
                // Если окно логина закрывается до того, как было показано главное окно
                if (!mainWindowIsActive) {
                    ioManager.outputLine("Login window closed by user. Exiting application.")
                    Platform.exit() // Завершаем приложение
                }
                // Если mainWindowIsActive == true, значит, мы уже перешли в главное окно,
                // и у него будет свой обработчик закрытия. Этот обработчик не должен срабатывать.
            }
            if (!stage.isShowing) { // Показываем, только если еще не показано (на случай вызова из onLogout)
                stage.show()
            }
        } catch (e: Exception) {
            handleFatalError("Failed to load LoginView.fxml", e)
        }
    }

    // Вызывается из LoginController при успешном логине
    fun onLoginSuccess(loginStage: Stage, username: String) {
        ioManager.outputLine("Login successful for $username! Proceeding to main application window.")
        mainWindowIsActive = true // Устанавливаем флаг
        showMainWindow(loginStage, username) // Передаем Stage и имя пользователя
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
// mainController.setLoggedInUser(loggedInUsername) // ЭТОТ ВЫЗОВ УБИРАЕМ
            mainController.userLoggedIn()  // Явно передаем имя пользователя

            val scene = Scene(root, 1200.0, 708.0) // Размеры из твоего FXML
            currentStage.scene = scene
            currentStage.minWidth = 1200.0
            currentStage.minHeight = 708.0
            currentStage.centerOnScreen()

            currentStage.setOnCloseRequest {
                ioManager.outputLine("Main window close request. Exiting application.")
                Platform.exit() // При закрытии главного окна - выходим из приложения
            }
            ioManager.outputLine("Main window shown for user $loggedInUsername.")
        } catch (e: Exception) {
            handleFatalError("Failed to load or show MainView.fxml", e)
        }
    }

    // Вызывается из MainController при нажатии кнопки Logout
    fun onLogout(mainStage: Stage) {
        ioManager.outputLine("User logged out. Returning to login screen.")
        // mainWindowIsActive будет сброшен в showLoginScreen


        showLoginScreen(mainStage) // Переиспользуем тот же Stage для окна логина
    }

    override fun stop() {
        super.stop()
        ioManager.outputLine("Application stopping...")
        if (::apiClient.isInitialized && apiClient.isRunning()) {
            apiClient.close() // Блокирующий вызов, дождется завершения nioThread
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
        throwable?.printStackTrace(System.err) // Выводим стектрейс в System.err
        if (Platform.isFxApplicationThread()) {
             Alert(Alert.AlertType.ERROR, fullMessage).showAndWait() // Можно показать Alert
        }
        Platform.exit()
    }
}

// Точка входа для запуска JavaFX приложения
fun main() {
    Application.launch(MainApp::class.java)
}