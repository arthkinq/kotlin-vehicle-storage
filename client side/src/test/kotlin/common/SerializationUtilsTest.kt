package common

import model.Vehicle
import model.Coordinates
import model.VehicleType
import model.FuelType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.ByteBuffer

class SerializationUtilsTest {

    @Test
    fun testRequestSerialization() {
        val request = Request(
            body = listOf("show"),
            vehicle = null,
            username = "testUser",
            password = "testPassword"
        )
        
        val buffer = SerializationUtils.objectToByteBuffer(request)
        
        // Simulating the reading state
        val readState = SerializationUtils.ObjectReaderState()
        
        // Ensure buffer is ready for reading
        assertTrue(buffer.remaining() > 4)
        
        assertTrue(readState.readLengthFromBuffer(buffer))
        assertTrue(readState.readObjectBytesFromBuffer(buffer))
        
        val deserializedRequest = readState.deserializeObject<Request>()!!
        
        assertEquals(request.body, deserializedRequest.body)
        assertEquals(request.username, deserializedRequest.username)
        assertEquals(request.password, deserializedRequest.password)
        assertNull(deserializedRequest.vehicle)
    }
    
    @Test
    fun testResponseSerialization() {
        val response = Response(
            responseText = "Success",
            vehicles = listOf(
                Vehicle(
                    id = 1,
                    name = "Boat",
                    coordinates = Coordinates(1, 2.0f),
                    creationDate = 1000L,
                    enginePower = 100.0,
                    distanceTravelled = 10.0,
                    type = VehicleType.BOAT,
                    fuelType = FuelType.DIESEL,
                    userId = 1
                )
            )
        )
        
        val buffer = SerializationUtils.objectToByteBuffer(response)
        
        val readState = SerializationUtils.ObjectReaderState()
        assertTrue(readState.readLengthFromBuffer(buffer))
        assertTrue(readState.readObjectBytesFromBuffer(buffer))
        
        val deserializedResponse = readState.deserializeObject<Response>()!!
        
        assertEquals(response.responseText, deserializedResponse.responseText)
        assertEquals(1, deserializedResponse.vehicles?.size)
        assertEquals("Boat", deserializedResponse.vehicles?.get(0)?.name)
    }
}
