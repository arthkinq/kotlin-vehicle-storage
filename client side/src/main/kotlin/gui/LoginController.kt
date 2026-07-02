package gui

import app.MainApp
import core.ApiClient
import common.Request
import javafx.application.Platform
import javafx.fxml.FXML
import javafx.scene.control.Button
import javafx.scene.control.PasswordField
import javafx.scene.control.TextField
import javafx.scene.control.Label
import javafx.stage.Stage

class LoginController {

    @FXML lateinit var statusText: Label
    @FXML private lateinit var usernameField: TextField
    @FXML private lateinit var passwordField: PasswordField
    @FXML private lateinit var loginButton: Button
    @FXML private lateinit var registerButton: Button

    private lateinit var apiClient: ApiClient
    private lateinit var mainApp: MainApp
    private lateinit var currentStage: Stage

    private var loginInProgress = false

    fun initialize() {

        setButtonsDisabled(false)

        usernameField.textProperty().addListener { _, _, _ -> if (!loginInProgress) statusText.text = "" }
        passwordField.textProperty().addListener { _, _, _ -> if (!loginInProgress) statusText.text = "" }
    }

    fun setApiClient(apiClient: ApiClient) {
        this.apiClient = apiClient

        apiClient.onConnectionStatusChanged = { isConnected, message ->
            Platform.runLater {
                if (!loginInProgress) {
                    val actualMessage = message ?: if (isConnected) "Network: Connected" else "Network: Disconnected. Click button to try."
                    statusText.text = actualMessage

                    setButtonsDisabled(false)
                }
            }
        }

        Platform.runLater {
            if (this::apiClient.isInitialized) {
                if (apiClient.isConnectionPending()) {
                    statusText.text = "Network: Attempting initial connection..."
                    setButtonsDisabled(true)
                } else if (apiClient.isConnected()) {
                    statusText.text = "Network: Connected"
                    setButtonsDisabled(false)
                } else {
                    statusText.text = "Network: Disconnected. Ready to connect."
                    setButtonsDisabled(false)
                }
            }
        }
    }

    fun setMainApp(mainApp: MainApp) {
        this.mainApp = mainApp
    }

    fun  setCurrentStage(stage: Stage) {
        this.currentStage = stage
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
            statusText.text = "Username and password cannot be empty."
            return
        }

        loginInProgress = true
        statusText.text = "Processing $commandName..."
        setButtonsDisabled(true)

        Thread {
            var connectionOK = apiClient.isConnected()
            if (!connectionOK) {
                Platform.runLater { statusText.text = "Attempting to connect to server..." }
                connectionOK = apiClient.connectIfNeeded()
            }

            if (!connectionOK) {
                Platform.runLater {

                    if (statusText.text.startsWith("Processing") || statusText.text.startsWith("Attempting to connect")) {
                        statusText.text = "Failed to connect to server. Please try again."
                    }
                    setButtonsDisabled(false)
                    loginInProgress = false
                }
                return@Thread
            }

            val request = Request(
                body = listOf(commandName, username, password),
                username = username,
                password = password
            )

            val response = apiClient.sendRequestAndWaitForResponse(request)

            Platform.runLater {
                if (response != null) {
                    statusText.text = response.responseText
                    if (!response.responseText.lowercase().contains("error:")) {
                        if (commandName == "login") {
                            apiClient.setCurrentUserCredentials(username, password)

                            mainApp.onLoginSuccess(currentStage, username)

                        } else if (commandName == "register") {
                            statusText.text = "${response.responseText} You can now login."
                            setButtonsDisabled(false)
                            loginInProgress = false
                        }
                    } else {

                        if (commandName == "login") {
                            apiClient.clearCurrentUserCredentials()
                        }
                        setButtonsDisabled(false)
                        loginInProgress = false
                    }
                } else {
                    statusText.text = "No response from server or request timed out for $commandName."
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