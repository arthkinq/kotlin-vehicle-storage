package gui

import app.MainApp
import core.ApiClient
import common.Request
import javafx.application.Platform
import javafx.fxml.FXML
import javafx.scene.control.Button
import javafx.scene.control.PasswordField
import javafx.scene.control.TextField
import javafx.scene.text.Text
import javafx.stage.Stage

class LoginController {
    @FXML
    lateinit var statusText: Text

    @FXML
    private lateinit var usernameField: TextField

    @FXML
    private lateinit var passwordField: PasswordField

    @FXML
    private lateinit var loginButton: Button

    @FXML
    private lateinit var registerButton: Button


    private lateinit var apiClient: ApiClient
    private lateinit var mainApp: MainApp
    private lateinit var currentStage: Stage

    fun setApiClient(apiClient: ApiClient) {
        this.apiClient = apiClient
        this.apiClient.onConnectionStatusChanged = { isConnected, message ->
            Platform.runLater {
                val actualMessage = message ?: if (isConnected) "Connected" else "Disconnected"
                if (!loginButton.isDisabled) {
                    statusText.text = "Network: $actualMessage"
                }
                if (!apiClient.isConnected() && !apiClient.isConnectionPending()) {
                    setButtonsDisabled(false)
                } else if (apiClient.isConnected() && !loginButton.isDisabled) {
                    setButtonsDisabled(false)
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
        val password = passwordField.text

        if (username.isBlank() || password.isBlank()) {
            statusText.text = "Username and password cannot be empty."
            return
        }

        statusText.text = "Processing..."
        setButtonsDisabled(true)

        Thread {
            var connectionOK = apiClient.isConnected()
            if (!connectionOK) {
                Platform.runLater { statusText.text = "Attempting to connect..." }
                connectionOK = apiClient.connectIfNeeded()
            }

            if (!connectionOK) {
                Platform.runLater {
                    setButtonsDisabled(false)
                }
                return@Thread  //??
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
                    if (!response.responseText.lowercase().contains("error")) {
                        if (commandName == "login") {
                            apiClient.setCurrentUserCredentials(username, password)
                            mainApp.onLoginSuccess(currentStage, username) // Переходим в MainApp
                        } else if (commandName == "register") {
                            statusText.text = "${response.responseText} You can now login."
                            setButtonsDisabled(false)
                        }
                    } else {
                        statusText.text = response.responseText
                        apiClient.clearCurrentUserCredentials()
                        setButtonsDisabled(false)
                    }
                } else {
                    statusText.text = "No response from server or request timed out for $commandName."
                    setButtonsDisabled(false)
                }
                if (!(commandName == "login" && !statusText.text.lowercase().contains("error"))) {
                    setButtonsDisabled(false)
                }
            }
        }.start()
    }

    private fun setButtonsDisabled(disabled: Boolean) {
        loginButton.isDisable = disabled
        registerButton.isDisable = disabled
    }

}