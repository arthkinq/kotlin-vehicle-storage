package org.example.commands

import org.example.IO.IOManager
import org.example.core.CollectionManager
import org.example.core.Response
import org.example.model.*

class AddCommand : Command("add", "Add new vehicle to collection", 0) {

    private fun createVehicleFromArgs(args: List<String>): Vehicle {
        val id = args[0].toIntOrNull() ?: throw IllegalArgumentException("ID must be an integer")
        val name = args[1]
        val coordinates = Coordinates(
            x = args[2].toIntOrNull() ?: throw IllegalArgumentException("Coordinates X must be a int"),
            y = args[3].toFloatOrNull() ?: throw IllegalArgumentException("Coordinates Y must be a float")
        )
        val creationDate = System.currentTimeMillis() // Текущее время в миллисекундах
        val enginePower = args[4].toDoubleOrNull() ?: throw IllegalArgumentException("Engine power must be a double")
        val distanceTravelled = args[5].toDoubleOrNull()
        val type = VehicleType.valueOf(args[6].uppercase()) // Преобразование строки в enum
        val fuelType =
            FuelType.valueOf(args[7].uppercase()) // Преобразование строки в enum // Можно добавить дополнительный аргумент, если нужно

        return Vehicle(
            id = id,
            name = name,
            coordinates = coordinates,
            creationDate = creationDate,
            enginePower = enginePower,
            distanceTravelled = distanceTravelled,
            type = type,
            fuelType = fuelType
        )
    }

    override fun execute(
        args: List<String>,
        collectionManager: CollectionManager,
        ioManager: IOManager,
        vehicle: Vehicle?
    ): Response {
        if (args.size == 8) {
            val vehicle = createVehicleFromArgs(args)
            collectionManager.addVehicle(vehicle)
            return Response("Vehicle added with ID: ${vehicle.id}")
        } else {
            return Response("Not enough arguements")
        }

    }
}