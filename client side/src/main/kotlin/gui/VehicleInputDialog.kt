package gui // или ваш пакет для GUI классов

import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.layout.GridPane
import javafx.stage.Window
import model.* // Импортируем ваши модели Vehicle, Coordinates, VehicleType, FuelType
import java.time.Instant // Для creationDate, если используем Long как timestamp
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class VehicleInputDialog(owner: Window, private val existingVehicle: Vehicle?) : Dialog<Vehicle>() {

    // Поля ввода GUI
    private val nameField = TextField()
    private val coordXField = TextField()
    private val coordYField = TextField()
    private val enginePowerField = TextField()
    private val distanceTravelledField = TextField()
    private val typeComboBox = ComboBox<VehicleType?>() // Nullable, если тип может отсутствовать
    private val fuelTypeComboBox = ComboBox<FuelType?>() // Nullable

    // Если у вас есть поле creationDate для отображения (но оно обычно генерируется)
    // private val creationDateField = TextField() // Сделать его non-editable, если генерируется

    init {
        initOwner(owner)
        title = if (existingVehicle == null) "Add New Vehicle" else "Edit Vehicle (ID: ${existingVehicle.id})"
        dialogPane.minWidth = 400.0 // Задаем минимальную ширину для диалога

        setupLayout()
        populateFieldsIfEditing()
        setupResultConverter()
    }

    private fun setupLayout() {
        val grid = GridPane().apply {
            hgap = 10.0
            vgap = 10.0
            padding = Insets(20.0, 20.0, 10.0, 20.0)
        }

        var rowIndex = 0
        grid.add(Label("Name*:"), 0, rowIndex)
        grid.add(nameField.apply { promptText = "Vehicle name" }, 1, rowIndex++)

        grid.add(Label("Coordinate X (≤806)*:"), 0, rowIndex)
        grid.add(coordXField.apply { promptText = "Integer, e.g., 100" }, 1, rowIndex++)

        grid.add(Label("Coordinate Y (≤922)*:"), 0, rowIndex)
        grid.add(coordYField.apply { promptText = "Number, e.g., 50.5" }, 1, rowIndex++)

        grid.add(Label("Engine Power (>0)*:"), 0, rowIndex)
        grid.add(enginePowerField.apply { promptText = "Number, e.g., 150.0" }, 1, rowIndex++)

        grid.add(Label("Distance Travelled (>0, optional):"), 0, rowIndex)
        grid.add(distanceTravelledField.apply { promptText = "Number or empty" }, 1, rowIndex++)

        grid.add(Label("Vehicle Type (optional):"), 0, rowIndex)
        typeComboBox.items.addAll(null) // Добавляем null как опцию "не выбрано"
        typeComboBox.items.addAll(VehicleType.entries)
        grid.add(typeComboBox, 1, rowIndex++)

        grid.add(Label("Fuel Type (optional):"), 0, rowIndex)
        fuelTypeComboBox.items.addAll(null) // Добавляем null как опцию "не выбрано"
        fuelTypeComboBox.items.addAll(FuelType.entries)
        grid.add(fuelTypeComboBox, 1, rowIndex++)

        // Если creationDate нужно отображать (обычно для редактирования, не для нового)
        // if (existingVehicle != null) {
        //     grid.add(Label("Creation Date:"), 0, rowIndex)
        //     val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
        //     creationDateField.text = formatter.format(Instant.ofEpochMilli(existingVehicle.creationDate))
        //     creationDateField.isEditable = false
        //     grid.add(creationDateField, 1, rowIndex++)
        // }


        dialogPane.content = grid

        val okButtonType = ButtonType("OK", ButtonBar.ButtonData.OK_DONE)
        dialogPane.buttonTypes.addAll(okButtonType, ButtonType.CANCEL)
    }

    private fun populateFieldsIfEditing() {
        existingVehicle?.let {
            nameField.text = it.name
            coordXField.text = it.coordinates.x.toString()
            coordYField.text = it.coordinates.y.toString()
            enginePowerField.text = it.enginePower.toString()
            distanceTravelledField.text = it.distanceTravelled?.toString() ?: ""
            typeComboBox.value = it.type
            fuelTypeComboBox.value = it.fuelType
        }
    }

    private fun setupResultConverter() {
        val okButton = dialogPane.lookupButton(dialogPane.buttonTypes.first { it.buttonData == ButtonBar.ButtonData.OK_DONE })

        // Простая валидация для активации кнопки OK (можно усложнить)
        // okButton.isDisable = true
        // nameField.textProperty().addListener { _, _, newValue ->
        //     okButton.isDisable = newValue.trim().isEmpty() || coordXField.text.trim().isEmpty() /* ... и т.д. ... */
        // }
        // coordXField.textProperty().addListener { /* ... */ }


        setResultConverter { dialogButton ->
            if (dialogButton.buttonData == ButtonBar.ButtonData.OK_DONE) {
                try {
                    val name = nameField.text.trim()
                    if (name.isEmpty()) throw ValidationException("Name cannot be empty.")

                    val x = coordXField.text.trim().toIntOrNull()
                        ?: throw ValidationException("X coordinate must be a valid integer.")
                    if (x > 806) throw ValidationException("X coordinate must be ≤ 806.")

                    val y = coordYField.text.trim().toFloatOrNull()
                        ?: throw ValidationException("Y coordinate must be a valid number.")
                    if (y > 922f) throw ValidationException("Y coordinate must be ≤ 922.")

                    val enginePower = enginePowerField.text.trim().toDoubleOrNull()
                        ?: throw ValidationException("Engine power must be a valid number.")
                    if (enginePower <= 0) throw ValidationException("Engine power must be > 0.")

                    val distanceStr = distanceTravelledField.text.trim()
                    val distanceTravelled: Double? = if (distanceStr.isEmpty()) {
                        null
                    } else {
                        val dist = distanceStr.toDoubleOrNull()
                            ?: throw ValidationException("Distance travelled must be a valid number if provided.")
                        if (dist <= 0) throw ValidationException("Distance travelled must be > 0 if provided.")
                        dist
                    }

                    val type: VehicleType? = typeComboBox.value
                    val fuelType: FuelType? = fuelTypeComboBox.value

                    // ID и creationDate:
                    // Для нового объекта ID будет присвоен сервером. creationDate генерируем сейчас или сервер.
                    // Для существующего объекта сохраняем старые ID и creationDate.
                    val id = existingVehicle?.id ?: 0 // Или другое значение-плейсхолдер для нового
                    val creationDate = existingVehicle?.creationDate ?: System.currentTimeMillis()
                    val userId = existingVehicle?.userId ?: 0 // TODO: Получать актуального userId, если нужно

                    return@setResultConverter Vehicle(
                        id = id,
                        name = name,
                        coordinates = Coordinates(x, y),
                        creationDate = creationDate,
                        enginePower = enginePower,
                        distanceTravelled = distanceTravelled,
                        type = type,
                        fuelType = fuelType,
                        userId = userId // Важно: userId должен быть актуальным
                    )

                } catch (e: ValidationException) {
                    showValidationError(e.message)
                    return@setResultConverter null // Остаемся в диалоге
                } catch (e: NumberFormatException) {
                    showValidationError("Invalid number format in one of the fields.")
                    return@setResultConverter null // Остаемся в диалоге
                } catch (e: Exception) { // Общий обработчик
                    showValidationError("An unexpected error occurred: ${e.message}")
                    return@setResultConverter null
                }
            }
            null // Для Cancel или закрытия окна
        }
    }

    // Отдельный метод для показа ошибки, чтобы не закрывать диалог
    private fun showValidationError(message: String?) {
        val alert = Alert(Alert.AlertType.ERROR).apply {
            initOwner(this@VehicleInputDialog.dialogPane.scene.window)
            title = "Validation Error"
            headerText = "Invalid input"
            contentText = message ?: "Please check your input."
        }
        alert.showAndWait()
    }

    fun showAndWaitWithResult(): Vehicle? {
        return showAndWait().orElse(null)
    }
}

// Вспомогательный класс для ошибок валидации
private class ValidationException(message: String) : RuntimeException(message)