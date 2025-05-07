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
        return Vehicle(
            id = idCounter.getAndIncrement(),
            name = data[0],
            coordinates = Coordinates(data[1].toInt(), data[2].toFloat()),
            creationDate = System.currentTimeMillis(),
            enginePower = data[3].toDouble(),
            distanceTravelled = data[4].toDoubleOrNull(),
            type = VehicleType.valueOf(data[5]),
            fuelType = FuelType.valueOf(data[6])
        )
    }
}