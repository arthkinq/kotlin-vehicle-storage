package core

import model.*
import myio.IOManager
import kotlin.NoSuchElementException // Уже есть

class VehicleReader(private var ioManager: IOManager) {
    fun readVehicle(): Vehicle {
        return Vehicle(
            id = 0,
            name = readNonEmptyString("Vehicle name"),
            coordinates = readCoordinates(),
            creationDate = System.currentTimeMillis(),
            enginePower = readPositiveDouble("Engine power"),
            distanceTravelled = readOptionalPositiveDouble("Distance travelled"),
            type = readEnum("Vehicle type", VehicleType::class.java),
            fuelType = readEnum("Fuel type", FuelType::class.java),
            userId = 0,
        )
    }

    private fun readCoordinates(): Coordinates {
        return Coordinates(
            x = readBoundedInt("Coordinate X", max = 806),
            y = readBoundedFloat("Coordinate Y", max = 922f)
        )
    }

    private fun readNonEmptyString(prompt: String): String {
        while (true) {
            ioManager.outputInline("$prompt: ")
            val input = ioManager.readLine().trim()
            if (input.isNotEmpty()) return input
            ioManager.outputLine("Field cannot be empty!")
        }
    }

    private fun readBoundedInt(prompt: String, min: Int = Int.MIN_VALUE, max: Int): Int {
        while (true) {
            ioManager.outputInline("$prompt (must be <= $max): ")
            val input = ioManager.readLine()
            try {
                val value = input.toInt()
                if (value in min..max) return value
                ioManager.outputLine("Value must be in range $min to $max.")
            } catch (e: NumberFormatException) {
                ioManager.outputLine("Incorrect input! Please enter an integer.")
            }
        }
    }

    private fun readBoundedFloat(prompt: String, min: Float = -Float.MAX_VALUE, max: Float): Float {
        while (true) {
            ioManager.outputInline("$prompt (must be <= $max): ")
            val input = ioManager.readLine()
            try {
                val value = input.toFloat()
                if (value in min..max) return value
                ioManager.outputLine("Value must be in range $min to $max.")
            } catch (e: NumberFormatException) {
                ioManager.outputLine("Incorrect input! Please enter a floating-point number.")
            }
        }
    }

    private fun readPositiveDouble(prompt: String): Double {
        while (true) {
            ioManager.outputInline("$prompt (must be > 0): ")
            val input = ioManager.readLine()
            try {
                val value = input.toDouble()
                if (value > 0) return value
                ioManager.outputLine("Value must be greater than 0.")
            } catch (e: NumberFormatException) {
                ioManager.outputLine("Incorrect input! Please enter a number.")
            }
        }
    }

    // ИСПРАВЛЕННЫЙ МЕТОД
    private fun readOptionalPositiveDouble(prompt: String): Double? {
        while (true) {
            ioManager.outputInline("$prompt (must be > 0, or leave empty if no value): ")
            val input = ioManager.readLine().trim()
            if (input.isEmpty()) {
                return null
            }
            try {
                val value = input.toDouble()
                if (value > 0) {
                    return value
                } else {
                    ioManager.outputLine("Distance travelled must be a positive number. Please try again or leave empty.")
                }
            } catch (e: NumberFormatException) {
                ioManager.outputLine("Incorrect input! Please enter a valid number or leave empty.")
            }
        }
    }

    private inline fun <reified T : Enum<T>> readEnum(prompt: String, enumClass: Class<T>): T? {
        val values = enumClass.enumConstants.joinToString { it.name }
        ioManager.outputLine("$prompt (available values: $values)")
        while (true) {
            ioManager.outputInline("Input value (leave empty to cancel): ")
            val input = ioManager.readLine().trim()
            if (input.isEmpty()) return null
            try {
                return enumClass.enumConstants.first { it.name.equals(input, ignoreCase = true) }
            } catch (e: NoSuchElementException) {
                ioManager.outputLine("Wrong value! Please choose from the available values or leave empty.")
            }
        }
    }

    fun readVehicleFromScript(data: List<String>): Vehicle {
        if (data.size < 7) throw IllegalArgumentException("Insufficient data lines for vehicle in script. Expected 7, got ${data.size}")

        val name = data[0].takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Name cannot be empty (from script line 1)")

        val x = data[1].toIntOrNull()
            ?: throw IllegalArgumentException("Invalid X coordinate in script: '${data[1]}' (from script line 2)")
        if (x > 806) throw IllegalArgumentException("X coordinate '$x' exceeds maximum 806 (from script line 2)")


        val y = data[2].toFloatOrNull()
            ?: throw IllegalArgumentException("Invalid Y coordinate in script: '${data[2]}' (from script line 3)")
        if (y > 922f) throw IllegalArgumentException("Y coordinate '$y' exceeds maximum 922.0 (from script line 3)")


        val enginePower = data[3].toDoubleOrNull()?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("Invalid or non-positive engine power in script: '${data[3]}' (from script line 4)")

        val distanceTravelledStr = data[4]
        val distanceTravelled = if (distanceTravelledStr.isBlank()) {
            null
        } else {
            distanceTravelledStr.toDoubleOrNull()?.takeIf { it > 0 }
                ?: throw IllegalArgumentException("Invalid or non-positive distance travelled in script: '$distanceTravelledStr' (from script line 5)")
        }

        val typeStr = data[5]
        val type = VehicleType.entries.firstOrNull { it.name.equals(typeStr, ignoreCase = true) }
            .also { if (typeStr.isNotBlank() && it == null) throw IllegalArgumentException("Invalid VehicleType in script: '$typeStr' (from script line 6)") }


        val fuelTypeStr = data[6]
        val fuelType = FuelType.entries.firstOrNull { it.name.equals(fuelTypeStr, ignoreCase = true) }
            .also { if (fuelTypeStr.isNotBlank() && it == null) throw IllegalArgumentException("Invalid FuelType in script: '$fuelTypeStr' (from script line 7)") }


        return Vehicle(
            id = 0,
            name = name,
            coordinates = Coordinates(x, y),
            creationDate = 0L,
            enginePower = enginePower,
            distanceTravelled = distanceTravelled,
            type = type,
            fuelType = fuelType,
            userId = 0
        )
    }
}