package org.example.core


import org.example.IO.IOManager
import org.example.model.*
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.NoSuchElementException

class VehicleReader(private var ioManager: IOManager) {
    private val validInputs = listOf("name", "coordinates", "enginePower", "distanceTravelled", "type", "fuelType")

    companion object {
        private val idCounter = AtomicInteger(1)
        fun clearId() = idCounter.set(1)
    }


    fun readUpdatesForVehicle(vehicle: Vehicle) {
        ioManager.outputLine("You can change: ${validInputs.joinToString(", ")}.")
        ioManager.outputLine("What do you want to change? > ")
        val input = ioManager.readLine()
        if (input in validInputs) {
            try {
                when (input) {
                    "name" -> vehicle.name = readNonEmptyString("Vehicle name")
                    "coordinates" -> vehicle.coordinates = readCoordinates()
                    "enginePower" -> vehicle.enginePower = readPositiveDouble("Engine power")
                    "distanceTravelled" -> vehicle.distanceTravelled = readOptionalDouble("Distance travelled")
                    "type" -> vehicle.type = readEnum("Vehicle type", VehicleType::class.java)
                    "fuelType" -> readEnum("Fuel type", FuelType::class.java)
                }
            } catch (e: IllegalArgumentException) {
                ioManager.error("Validation error: ${e.message}")
            } catch (e: InputMismatchException) {
                ioManager.error("Format error: ${e.message}")
            }
        } else {
            ioManager.outputLine("Wrong input. Please enter one of these commands: ${validInputs.joinToString(", ")}.")
        }
    }

    fun readVehicle(): Vehicle {
        return Vehicle(
            id = idCounter.getAndIncrement(),  // Временное значение
            name = readNonEmptyString("Vehicle name"),
            coordinates = readCoordinates(),
            creationDate = System.currentTimeMillis(),
            enginePower = readPositiveDouble("Engine power"),
            distanceTravelled = readOptionalDouble("Distance travelled"),
            type = readEnum("Vehicle type", VehicleType::class.java),
            fuelType = readEnum("Fuel type", FuelType::class.java)
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
            ioManager.outputInline("$prompt (<$max): ")
            val input = ioManager.readLine()
            try {
                val value = input.toInt()
                if (value in min..max) return value
                ioManager.outputLine("Value must be in range $min to $max")
            } catch (e: NumberFormatException) {
                ioManager.outputLine("Wrong INT!")
            }
        }
    }

    private fun readBoundedFloat(prompt: String, min: Float = -Float.MAX_VALUE, max: Float): Float {
        while (true) {
            ioManager.outputInline("$prompt (max. $max): ")
            val input = ioManager.readLine()
            try {
                val value = input.toFloat()
                if (value in min..max) return value
                ioManager.outputLine("Value must be in range $min to $max")
            } catch (e: NumberFormatException) {
                ioManager.outputLine("Incorrect value!")
            }
        }
    }

    private fun readPositiveDouble(prompt: String): Double {
        while (true) {
            ioManager.outputInline("$prompt: ")
            val input = ioManager.readLine()
            try {
                val value = input.toDouble()
                if (value > 0) return value
                ioManager.outputLine("Value must not be 0")
            } catch (e: NumberFormatException) {
                ioManager.outputLine("Incorrect value!")
            }
        }
    }

    private fun readOptionalDouble(prompt: String): Double? {
        ioManager.outputInline("$prompt (leave empty if no value): ")
        val input = ioManager.readLine().trim()
        return if (input.isEmpty()) {
            null
        } else {
            try {
                input.toDouble().takeIf { it > 0 }
                    ?: throw IllegalArgumentException("Value must be positive")
            } catch (e: NumberFormatException) {
                ioManager.outputLine("Wrong value! Filed will equal null")
                null
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
                ioManager.outputLine("Wrong value! Try again!")
            }
        }
    }

    fun readVehicleFromScript(data: List<String>): Vehicle {
        if (data.size < 7) throw IllegalArgumentException("Insufficient data lines for vehicle in script. Expected 7, got ${data.size}")
        val name =
            data[0].takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Name cannot be empty (from script)")
        val x = data[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid X coordinate in script: ${data[1]}")
        val y = data[2].toFloatOrNull() ?: throw IllegalArgumentException("Invalid Y coordinate in script: ${data[2]}")
        val enginePower = data[3].toDoubleOrNull()?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("Invalid or non-positive engine power in script: ${data[3]}")
        val distanceTravelled = data[4].takeIf { it.isNotBlank() }?.toDoubleOrNull()?.takeIf { it > 0 }
        val typeStr = data[5]
        val type = VehicleType.entries.firstOrNull { it.name.equals(typeStr, ignoreCase = true) }
        val fuelType = FuelType.entries.firstOrNull { it.name.equals(data[6], ignoreCase = true) }
        return Vehicle(
            id = 0, // Server will assign ID
            name = name,
            coordinates = Coordinates(x, y), // Coordinates class will validate
            creationDate = 0L, // Server will assign
            enginePower = enginePower,
            distanceTravelled = distanceTravelled,
            type = type,
            fuelType = fuelType
        )
    }
}