package gui

import app.MainApp
import common.Request
import core.ApiClient
import javafx.application.Platform
import javafx.fxml.FXML
import javafx.scene.control.*
import javafx.scene.text.Text
import javafx.stage.Stage
import util.LocaleManager // Импортируем наш LocaleManager
import java.util.Locale   // Импортируем Locale

class LoginController {

    // Добавляем @FXML для новых элементов
    @FXML private lateinit var appTitleText: Text
    @FXML private lateinit var headerText: Text
    @FXML private lateinit var usernameLabel: Label
    @FXML private lateinit var passwordLabel: Label
    @FXML private lateinit var languageComboBox: ComboBox<Locale>

    // Существующие @FXML поля
    @FXML lateinit var statusText: Text // Оставляем public, если нужно извне менять, но лучше private
    @FXML private lateinit var usernameField: TextField
    @FXML private lateinit var passwordField: PasswordField
    @FXML private lateinit var loginButton: Button
    @FXML private lateinit var registerButton: Button

    private lateinit var apiClient: ApiClient
    private lateinit var mainApp: MainApp
    private lateinit var currentStage: Stage

    private var loginInProgress = false

    @FXML // Указываем, что это метод, вызываемый FXML (хотя для initialize это необязательно, если он public)
    fun initialize() {
        setButtonsDisabled(false)
        usernameField.textProperty().addListener { _, _, _ -> if (!loginInProgress) clearStatusText() }
        passwordField.textProperty().addListener { _, _, _ -> if (!loginInProgress) clearStatusText() }

        // Инициализация ComboBox для выбора языка
        languageComboBox.items.addAll(LocaleManager.supportedLocales)
        languageComboBox.value = LocaleManager.currentLocale
        languageComboBox.setCellFactory { LanguageListCell() } // LanguageListCell должен быть доступен
        languageComboBox.buttonCell = LanguageListCell()

        languageComboBox.valueProperty().addListener { _, _, newLocale ->
            if (newLocale != null && newLocale != LocaleManager.currentLocale) {
                LocaleManager.currentLocale = newLocale
                // updateTexts() вызовется через слушателя ниже
            }
        }

        // Слушатель для обновления UI при смене локали из LocaleManager
        LocaleManager.currentLocaleProperty.addListener { _, _, _ -> updateTexts() }
        updateTexts() // Первоначальная установка всех текстов
    }

    private fun updateTexts() {
        // Заголовок окна Stage
        if (::currentStage.isInitialized) { // Используем currentStage, так как loginStage убрал
            currentStage.titleProperty().unbind() // Отвязываем на всякий случай
            currentStage.title = LocaleManager.getString("login.appTitle")
        }

        appTitleText.textProperty().bind(LocaleManager.getObservableString("login.appTitle"))
        headerText.textProperty().bind(LocaleManager.getObservableString("login.header"))
        usernameLabel.textProperty().bind(LocaleManager.getObservableString("login.label.username"))
        usernameField.promptTextProperty().bind(LocaleManager.getObservableString("login.prompt.username"))
        passwordLabel.textProperty().bind(LocaleManager.getObservableString("login.label.password"))
        passwordField.promptTextProperty().bind(LocaleManager.getObservableString("login.prompt.password"))
        loginButton.textProperty().bind(LocaleManager.getObservableString("login.button.login"))
        registerButton.textProperty().bind(LocaleManager.getObservableString("login.button.register"))

        // Обновляем statusText, если он не содержит сообщения об ошибке/процессе
        // Это важно, чтобы не затереть актуальное сообщение о статусе операции
        if (statusText.text.isEmpty() || statusText.text == LocaleManager.getString("login.status.placeholder") ||
            statusText.text.startsWith("Network:")) { // Не затираем сообщения о сети
            clearStatusText() // Устанавливает плейсхолдер или пустоту
        }
    }

    private fun clearStatusText() {
        statusText.text = LocaleManager.getString("login.status.placeholder")
    }

    fun setApiClient(apiClient: ApiClient) {
        this.apiClient = apiClient
        apiClient.onConnectionStatusChanged = { isConnected, message ->
            Platform.runLater {
                if (!loginInProgress) {
                    val actualMessageKey: String
                    val messageParam: String?
                    if (message != null) {
                        // Если есть кастомное сообщение, его не локализуем, просто показываем
                        statusText.text = message
                        setButtonsDisabled(false) // Предполагаем, что кнопки должны быть активны
                        return@runLater
                    } else {
                        actualMessageKey = if (isConnected) "network.status.connected" else "network.status.disconnected"
                        messageParam = null
                    }
                    statusText.text = if (messageParam != null) LocaleManager.getString(actualMessageKey, messageParam)
                    else LocaleManager.getString(actualMessageKey)
                    setButtonsDisabled(false)
                }
            }
        }
        // Обновляем UI при первоначальной установке apiClient
        Platform.runLater {
            if (this::apiClient.isInitialized) {
                val statusKey: String
                if (apiClient.isConnectionPending()) {
                    statusKey = "network.status.pending"
                    setButtonsDisabled(true)
                } else if (apiClient.isConnected()) {
                    statusKey = "network.status.connected"
                    setButtonsDisabled(false)
                } else {
                    statusKey = "network.status.disconnectedReady"
                    setButtonsDisabled(false)
                }
                statusText.text = LocaleManager.getString(statusKey)
            }
        }
    }

    fun setMainApp(mainApp: MainApp) {
        this.mainApp = mainApp
    }

    // Переименовал в setCurrentStage для единообразия с MainController
    fun setCurrentStage(stage: Stage) {
        this.currentStage = stage
        // Установка заголовка окна при передаче Stage
        if (::currentStage.isInitialized) {
            currentStage.titleProperty().unbind()
            currentStage.title = LocaleManager.getString("login.appTitle")
            // Добавляем слушателя на смену локали для заголовка окна, если он не привязан через property
            LocaleManager.currentLocaleProperty.addListener { _, _, _ ->
                currentStage.title = LocaleManager.getString("login.appTitle")
            }
        }
    }

    @FXML
    private fun handleLogin() {
        performAuthAction("login")
    }

    @FXML
    private fun handleRegister() {
        performAuthAction("register")
    }

    private fun performAuthAction(commandName: String) {
        if (loginInProgress) return

        val username = usernameField.text.trim()
        val password = passwordField.text

        if (username.isBlank() || password.isBlank()) {
            statusText.text = LocaleManager.getString("login.error.emptyFields")
            return
        }

        loginInProgress = true
        statusText.text = LocaleManager.getString("login.status.processing", commandName)
        setButtonsDisabled(true)

        Thread {
            var connectionOK = apiClient.isConnected()
            if (!connectionOK) {
                Platform.runLater { statusText.text = LocaleManager.getString("login.status.connecting") }
                connectionOK = apiClient.connectIfNeeded()
            }

            if (!connectionOK) {
                Platform.runLater {
                    if (statusText.text.startsWith(LocaleManager.getString("login.status.processing", "").substringBefore("{0}")) || // Проверяем начало "Processing"
                        statusText.text == LocaleManager.getString("login.status.connecting")) {
                        statusText.text = LocaleManager.getString("login.error.connectFailed")
                    }
                    // Сообщение об ошибке также может прийти от onConnectionStatusChanged
                    setButtonsDisabled(false)
                    loginInProgress = false
                }
                return@Thread
            }

            val request = Request(
                body = listOf(commandName, username, password),
                username = username, // username и password в Request дублируют те, что в body, но это ОК
                password = password
            )

            val response = apiClient.sendRequestAndWaitForResponse(request)

            Platform.runLater {
                if (response != null) {
                    // statusText.text = response.responseText // Не локализовано!
                    // Лучше, чтобы сервер возвращал ключи или коды.
                    // Пока просто отображаем, но помечаем как TODO для локализации ответа сервера.
                    // Если response.responseText это ключ, то:
                    // statusText.text = LocaleManager.getString(response.responseText)
                    // Иначе:
                    statusText.text = response.responseText // TODO: Localize server responses if possible

                    if (!response.responseText.lowercase().contains("error:")) {
                        if (commandName == "login") {
                            apiClient.setCurrentUserCredentials(username, password)
                            mainApp.onLoginSuccess(currentStage, username) // Передаем username
                            // loginInProgress не сбрасываем
                        } else if (commandName == "register") {
                            // Формируем сообщение об успехе регистрации
                            statusText.text = LocaleManager.getString("login.success.register", response.responseText)
                            setButtonsDisabled(false)
                            loginInProgress = false
                        }
                    } else { // Ответ сервера содержит "error:"
                        // statusText.text уже установлен выше (ответ сервера)
                        if (commandName == "login") {
                            apiClient.clearCurrentUserCredentials()
                        }
                        setButtonsDisabled(false)
                        loginInProgress = false
                    }
                } else { // response == null
                    statusText.text = LocaleManager.getString("login.error.noResponse", commandName)
                    setButtonsDisabled(false)
                    loginInProgress = false
                }
            }
        }.start()
    }

    private fun setButtonsDisabled(disabled: Boolean) {
        loginButton.isDisable = disabled
        registerButton.isDisable = disabled
    }
}