package gui

import app.MainApp
import core.ApiClient
import common.Request // Убедись, что Request импортирован
import javafx.application.Platform
import javafx.fxml.FXML
import javafx.scene.control.Button
import javafx.scene.control.PasswordField
import javafx.scene.control.TextField
import javafx.scene.text.Text
import javafx.stage.Stage

class LoginController {

    @FXML private lateinit var usernameField: TextField
    @FXML private lateinit var passwordField: PasswordField
    @FXML private lateinit var loginButton: Button
    @FXML private lateinit var registerButton: Button
    @FXML private lateinit var statusText: Text

    private lateinit var apiClient: ApiClient
    private lateinit var mainApp: MainApp
    private lateinit var currentStage: Stage // Stage, на котором отображается это окно

    private var loginWasSuccessful: Boolean = false

    fun setApiClient(apiClient: ApiClient) {
        this.apiClient = apiClient
        // Подписываемся на изменения статуса соединения для обновления UI
        // (этот callback будет общим для всего клиента, MainApp тоже может его слушать)
        this.apiClient.onConnectionStatusChanged = { isConnected, message ->
            Platform.runLater {
                // Обновляем statusText только если это сообщение об ошибке подключения
                // Успешные сообщения или общие статусы лучше выводить в MainApp или статус-баре
                if (!isConnected && message?.contains("failed", ignoreCase = true) == true ||
                    message?.contains("refused", ignoreCase = true) == true ||
                    message?.contains("error", ignoreCase = true) == true) {
                    statusText.text = message
                } else if (message != null && statusText.text.startsWith("Processing")) {
                    // Если было "Processing...", а теперь пришло другое сообщение (например, "Connected")
                    // то его тоже можно отобразить, или очистить statusText.
                    // Для простоты пока оставим так.
                }
                // Обновляем состояние кнопок в зависимости от статуса подключения и выполнения операции
                if (!loginButton.isDisabled) { // Если кнопки не были выключены другой операцией
                    val disableButtons = !isConnected && !apiClient.isConnectionPending()
                    loginButton.isDisable = disableButtons
                    registerButton.isDisable = disableButtons
                }
            }
        }
    }

    fun setMainApp(mainApp: MainApp) {
        this.mainApp = mainApp
    }

    fun setCurrentStage(stage: Stage) {
        this.currentStage = stage
    }

    fun isLoginSuccessful(): Boolean = loginWasSuccessful

    @FXML
    private fun handleLogin() {
        performAuthAction("login")
    }

    @FXML
    private fun handleRegister() {
        performAuthAction("register")
    }

    private fun performAuthAction(commandName: String) {
        val username = usernameField.text.trim()
        val password = passwordField.text // Пароль не тримим, пробелы могут быть его частью

        if (username.isBlank() || password.isBlank()) {
            statusText.text = "Username and password cannot be empty."
            return
        }

        statusText.text = "Processing $commandName..."
        setButtonsDisabled(true)

        Thread { // Выполняем сетевой запрос в фоновом потоке
            var connectionOK = apiClient.isConnected()
            if (!connectionOK) {
                Platform.runLater { statusText.text = "Attempting to connect for $commandName..." }
                connectionOK = apiClient.connectIfNeeded() // Этот метод блокирует текущий (фоновый) поток
            }

            if (!connectionOK) {
                Platform.runLater {
                    statusText.text = "Failed to connect to server. Please try again."
                    setButtonsDisabled(false)
                }
                return@Thread
            }

            // Если подключились, формируем и отправляем запрос
            val request = Request(
                body = listOf(commandName, username, password), // Сервер ожидает креды в body для login/register
                username = username,
                password = password
            )

            val response = apiClient.sendRequestAndWaitForResponse(request)

            Platform.runLater { // Обновляем UI в JavaFX Application Thread
                if (response != null) {
                    statusText.text = response.responseText
                    if (!response.responseText.lowercase().contains("error")) {
                        loginWasSuccessful = true
                        if (commandName == "login") {
                            // Передаем управление в MainApp для показа главного окна
                            mainApp.onLoginSuccess(currentStage)
                        }
                        // Для register можно просто сообщить об успехе, окно закроется или останется
                        // (в зависимости от логики onLoginSuccess, если она вызывается и для register)
                        // Если для register не нужно переходить в главное окно, а просто закрыть это:
                        // if (commandName == "register") currentStage.close()
                    }
                } else {
                    statusText.text = "No response from server or request timed out for $commandName."
                }
                setButtonsDisabled(false) // Включаем кнопки обратно после получения ответа или ошибки
            }
        }.start()
    }

    private fun setButtonsDisabled(disabled: Boolean) {
        loginButton.isDisable = disabled
        registerButton.isDisable = disabled
    }

    // Вызывается JavaFX после инициализации всех @FXML полей
    fun initialize() {
        // Можно добавить слушателей на поля ввода, если нужно
        // Например, очищать statusText при начале ввода
        usernameField.textProperty().addListener { _, _, _ -> statusText.text = "" }
        passwordField.textProperty().addListener { _, _, _ -> statusText.text = "" }
    }
}