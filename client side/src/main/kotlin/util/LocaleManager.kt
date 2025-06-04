package util // или gui.util, или любой другой подходящий пакет

import java.text.MessageFormat
import java.util.Locale
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.beans.value.ObservableStringValue

object LocaleManager {

    val RUSSIAN = Locale("ru")
    val ESTONIAN = Locale("et")
    val BULGARIAN = Locale("bg")
    val CANADIAN_ENGLISH = Locale("en", "CA")

    // Список поддерживаемых локалей и локаль по умолчанию
    val supportedLocales: List<Locale> = listOf(RUSSIAN, ESTONIAN, BULGARIAN, CANADIAN_ENGLISH)
    val defaultLocale: Locale = CANADIAN_ENGLISH

    val currentLocaleProperty = SimpleObjectProperty<Locale>(defaultLocale)
    var currentLocale: Locale
        get() = currentLocaleProperty.get()
        set(value) {
            if (supportedLocales.contains(value)) {
                currentLocaleProperty.set(value)
            } else {
                System.err.println("Warning: Locale $value is not supported. Using default $defaultLocale.")
                currentLocaleProperty.set(defaultLocale)
            }
        }

    private val translations = mutableMapOf<Locale, Map<String, String>>()

    init {
        loadTranslations()
    }

    private fun loadTranslations() {
        translations[RUSSIAN] = mapOf(
            // LoginView.fxml
            "login.appTitle" to "Менеджер Транспорта",
            "login.header" to "Авторизация",
            "login.label.username" to "Логин:",
            "login.prompt.username" to "Ваш логин",
            "login.label.password" to "Пароль:",
            "login.prompt.password" to "Ваш пароль",
            "login.button.login" to "Войти",
            "login.button.register" to "Зарегистрироваться",
            "login.status.placeholder" to "",

            // MainView.fxml
            "main.appTitle" to "Менеджер Транспорта",
            "main.label.connectionStatus" to "Статус соединения:",
            "main.label.currentUser" to "Пользователь:",
            "main.button.logout" to "Выйти",
            "main.tab.map" to "Расположение объектов",
            "main.tab.table" to "Таблица",
            "main.text.commands" to "Команды:",

            // Колонки TableView
            "column.id" to "id",
            "column.name" to "имя",
            "column.coordX" to "коорд. X",
            "column.coordY" to "коорд. Y",
            "column.creationDate" to "дата созд.",
            "column.enginePower" to "мощн. двиг.",
            "column.distance" to "пробег",
            "column.type" to "тип",
            "column.fuelType" to "тип топл.",
            "column.userId" to "id польз.",

            "status.connected" to "Подключено",
            "status.disconnected" to "Отключено",
            "status.notLoggedIn" to "Не авторизован",
            "dialog.inputFor" to "Ввод для {0}",
            "dialog.enterArg" to "Введите аргумент ''{0}'' ({1}):"
        )

        translations[CANADIAN_ENGLISH] = mapOf(
            // LoginView.fxml
            "login.appTitle" to "Vehicle Manager",
            "login.header" to "Authorization",
            "login.label.username" to "Login:",
            "login.prompt.username" to "Your login",
            "login.label.password" to "Password:",
            "login.prompt.password" to "Your password",
            "login.button.login" to "Login",
            "login.button.register" to "Register",
            "login.status.placeholder" to "",

            // MainView.fxml
            "main.appTitle" to "Vehicle Manager",
            "main.label.connectionStatus" to "Connection Status:",
            "main.label.currentUser" to "User:",
            "main.button.logout" to "Logout",
            "main.tab.map" to "Object Locations",
            "main.tab.table" to "Table",
            "main.text.commands" to "Commands:",

            // Колонки TableView
            "column.id" to "id",
            "column.name" to "name",
            "column.coordX" to "coord X",
            "column.coordY" to "coord Y",
            "column.creationDate" to "creation date",
            "column.enginePower" to "engine pwr",
            "column.distance" to "distance",
            "column.type" to "type",
            "column.fuelType" to "fuel type",
            "column.userId" to "user id",

            "status.connected" to "Connected",
            "status.disconnected" to "Disconnected",
            "status.notLoggedIn" to "Not logged in",
            "dialog.inputFor" to "Input for {0}",
            "dialog.enterArg" to "Enter argument ''{0}'' ({1}):"
        )

        // --- Эстонский ---
        translations[ESTONIAN] = mapOf(
            // LoginView.fxml
            "login.appTitle" to "Sõidukihaldur",
            "login.header" to "Autoriseerimine",
            "login.label.username" to "Kasutajanimi:",
            "login.prompt.username" to "Sinu kasutajanimi",
            "login.label.password" to "Parool:",
            "login.prompt.password" to "Sinu parool",
            "login.button.login" to "Logi sisse",
            "login.button.register" to "Registreeri",
            "login.status.placeholder" to "",

            // MainView.fxml
            "main.appTitle" to "Sõidukihaldur",
            "main.label.connectionStatus" to "Ühenduse Olek:",
            "main.label.currentUser" to "Kasutaja:",
            "main.button.logout" to "Logi välja",
            "main.tab.map" to "Objektide Asukohad",
            "main.tab.table" to "Tabel",
            "main.text.commands" to "Käsud:",

            "column.id" to "id",
            "column.name" to "nimi",
            "column.coordX" to "koord X",
            "column.coordY" to "koord Y",
            "column.creationDate" to "loomise kp",
            "column.enginePower" to "mootori võimsus",
            "column.distance" to "läbisõit",
            "column.type" to "tüüp",
            "column.fuelType" to "kütuse tüüp",
            "column.userId" to "kasutaja id",

            "status.connected" to "Ühendatud",
            "status.disconnected" to "Ühenduseta",
            "status.notLoggedIn" to "Sisse logimata",
            "dialog.inputFor" to "Sisend {0} jaoks",
            "dialog.enterArg" to "Sisesta argument ''{0}'' ({1}):"
        )

        // --- Болгарский ---
        translations[BULGARIAN] = mapOf(
            // LoginView.fxml
            "login.appTitle" to "Мениджър на превозни средства",
            "login.header" to "Авторизация",
            "login.label.username" to "Потребителско име:",
            "login.prompt.username" to "Вашето потребителско име",
            "login.label.password" to "Парола:",
            "login.prompt.password" to "Вашата парола",
            "login.button.login" to "Вход",
            "login.button.register" to "Регистрация",
            "login.status.placeholder" to "",

            // MainView.fxml
            "main.appTitle" to "Мениджър на превозни средства",
            "main.label.connectionStatus" to "Статус на връзката:",
            "main.label.currentUser" to "Потребител:",
            "main.button.logout" to "Изход",
            "main.tab.map" to "Местоположение на обекти",
            "main.tab.table" to "Таблица",
            "main.text.commands" to "Команди:",

            "column.id" to "id",
            "column.name" to "име",
            "column.coordX" to "коорд. X",
            "column.coordY" to "коорд. Y",
            "column.creationDate" to "дата на създ.",
            "column.enginePower" to "мощност на двиг.",
            "column.distance" to "пробег",
            "column.type" to "тип",
            "column.fuelType" to "тип гориво",
            "column.userId" to "id на потр.",

            "status.connected" to "Свързан",
            "status.disconnected" to "Несвързан",
            "status.notLoggedIn" to "Не сте влезли",
            "dialog.inputFor" to "Въвеждане за {0}",
            "dialog.enterArg" to "Въведете аргумент ''{0}'' ({1}):"
        )
    }

    fun getString(key: String): String {
        return translations[currentLocale]?.get(key)
            ?: translations[defaultLocale]?.get(key)
            ?: key.also { System.err.println("Warning: Missing translation for key '$key' in locale '${currentLocale}' and default locale '${defaultLocale}'.") }
    }

    fun getString(key: String, vararg args: Any): String {
        val pattern = getString(key)
        return MessageFormat.format(pattern, *args)
    }

    fun getObservableString(key: String): ObservableStringValue {
        val stringProp = SimpleStringProperty(getString(key))
        currentLocaleProperty.addListener { _, _, _ ->
            stringProp.set(getString(key))
        }
        return stringProp
    }

    fun getObservableString(key: String, vararg args: Any): ObservableStringValue {
        val stringProp = SimpleStringProperty(getString(key, *args))
        currentLocaleProperty.addListener { _, _, _ ->
            stringProp.set(getString(key, *args))
        }

        return stringProp
    }
}