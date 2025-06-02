package app

import core.ApiClient
import gui.LoginController
import javafx.application.Application
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.stage.Stage
import myio.ConsoleOutputManager
import myio.IOManager
import myio.InputManager // Импортируй свой интерфейс InputManager
import java.io.IOException
import java.util.logging.Level // Для логгера, если будешь использовать

class MainApp : Application() {

    private lateinit var apiClient: ApiClient
    private lateinit var ioManagerForApi: IOManager // Для сообщений ApiClient в консоль

    override fun init() {
        super.init()
        // IOManager для ApiClient
        ioManagerForApi = IOManager(
            input = object : InputManager { // Реализация InputManager-заглушки
                override fun readLine(): String? {
                    // ApiClient в GUI режиме не должен пытаться читать из консоли
                    System.err.println("WARNING: ApiClient.readLine() called in GUI mode!")
                    return "" // или null
                }
                override fun hasInput(): Boolean {
                    return false
                }
            },
            output = ConsoleOutputManager()
        )
        // Инициализация ApiClient. Убедись, что хост и порт корректны для твоего сервера.
        apiClient = ApiClient(ioManagerForApi, serverHost = "localhost", serverPort = 8888)
    }

    override fun start(primaryStage: Stage) {
        primaryStage.title = "Login - Transport Manager" // Начальный заголовок окна

        try {
            // Загружаем FXML для окна логина
            // Путь к FXML должен быть относительно classpath.
            // Если LoginView.fxml лежит в src/main/resources/gui/, то путь "/gui/LoginView.fxml"
            val loader = FXMLLoader(javaClass.getResource("/gui/LoginView.fxml"))
            val root: Parent = loader.load()

            // Получаем контроллер и передаем ему зависимости
            val loginController = loader.getController<LoginController>()
            loginController.setApiClient(apiClient)
            loginController.setMainApp(this)
            loginController.setCurrentStage(primaryStage) // Передаем primaryStage как текущий stage для LoginController

            val scene = Scene(root)
            primaryStage.scene = scene
            primaryStage.setOnCloseRequest { // Обработчик закрытия окна логина крестиком
                if (!loginController.isLoginSuccessful()) { // Если логин не был успешен
                    onLoginCancelledOrClosed()
                }
                // Если логин успешен, окно закроется из LoginController, и этот обработчик не нужен
                // или можно добавить apiClient.close() здесь, если это единственное окно
            }
            primaryStage.show()

        } catch (e: IOException) {
            e.printStackTrace() // Для отладки
            ioManagerForApi.error("Failed to load LoginView.fxml: ${e.message}")
            // Здесь можно показать Alert пользователю
            Platform.exit()
        } catch (e: Exception) {
            e.printStackTrace()
            ioManagerForApi.error("An unexpected error occurred during startup: ${e.message}")
            Platform.exit()
        }
    }

    // Вызывается из LoginController при успешном логине
    fun onLoginSuccess(loginStage: Stage) {
        ioManagerForApi.outputLine("Login successful! Proceeding to main application window.")
        showMainWindow(loginStage)
    }

    // Вызывается, если окно логина было закрыто до успешного логина
    fun onLoginCancelledOrClosed() {
        ioManagerForApi.outputLine("Login cancelled or window closed. Exiting application.")
        // apiClient.close() // ApiClient закроется в stop()
        Platform.exit()
    }

    fun showMainWindow(currentStage: Stage) {
        // currentStage - это тот же Stage, на котором было окно логина (бывший primaryStage)
        // Мы можем переиспользовать его для главного окна.
        currentStage.title = "Transport Manager - Main"
        currentStage.scene = null // Очищаем старую сцену (окна логина)

        try {
            ioManagerForApi.outputLine("Attempting to load MainView.fxml...")
            // TODO: Загрузить FXML для главного окна (например, MainView.fxml)
            // TODO: Создать gui.MainController и связать его с MainView.fxml
            // TODO: Передать apiClient и mainApp в MainController

            // Пример заглушки для главного окна:
            val placeholderRoot = javafx.scene.layout.VBox(
                javafx.scene.control.Label("Main Application Window"),
                javafx.scene.control.Label("User: ${apiClient.getCurrentUserCredentials()?.first ?: "N/A"}") // Пример отображения пользователя
            ).apply {
                alignment = javafx.geometry.Pos.CENTER
                spacing = 20.0
            }
            val scene = Scene(placeholderRoot, 800.0, 600.0)
            currentStage.scene = scene
            currentStage.setOnCloseRequest { // Обработчик закрытия главного окна
                apiClient.close() // Закрываем соединение перед выходом
                Platform.exit()
            }
            // currentStage.show() // Не нужно, если Stage уже был показан (просто меняем сцену)
            ioManagerForApi.outputLine("Main window placeholder shown.")

        } catch (e: IOException) {
            e.printStackTrace()
            ioManagerForApi.error("Failed to load MainView.fxml: ${e.message}")
            Platform.exit()
        } catch (e: Exception) {
            e.printStackTrace()
            ioManagerForApi.error("An unexpected error occurred showing main window: ${e.message}")
            Platform.exit()
        }
    }

    override fun stop() {
        super.stop()
        // Корректно завершаем работу ApiClient, если он еще работает
        if (::apiClient.isInitialized && apiClient.isRunning()) { // Проверяем, что apiClient был инициализирован
            apiClient.close()
            ioManagerForApi.outputLine("ApiClient stopped from MainApp.stop().")
        }
        ioManagerForApi.outputLine("Application stopped.")
    }
}

// Точка входа для запуска JavaFX приложения
fun main() {
    Application.launch(MainApp::class.java)
}