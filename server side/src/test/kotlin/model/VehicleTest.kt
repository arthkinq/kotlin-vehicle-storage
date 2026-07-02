package model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.Instant

class VehicleTest {

    @Test
    fun testVehicleCreation() {
        val vehicle = Vehicle(
            id = 1,
            name = "Test Boat",
            coordinates = Coordinates(10, 20.0f),
            creationDate = Instant.now().toEpochMilli(),
            enginePower = 150.0,
            distanceTravelled = 5000.0,
            type = VehicleType.BOAT,
            fuelType = FuelType.DIESEL,
            userId = 42
        )

        assertEquals("Test Boat", vehicle.name)
        assertEquals(10, vehicle.coordinates.x)
        assertEquals(20.0f, vehicle.coordinates.y)
        assertEquals(150.0, vehicle.enginePower)
        assertEquals(VehicleType.BOAT, vehicle.type)
        assertEquals(FuelType.DIESEL, vehicle.fuelType)
    }
}
