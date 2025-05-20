package common

import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

object SerializationUtils {


    val jsonFormat = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        isLenient = true
    }

    inline fun <reified T : Any> objectToByteBuffer(obj: T): ByteBuffer {
        val serializer = serializer<T>()
        val objectBytes = jsonFormat.encodeToString(serializer, obj).toByteArray(Charsets.UTF_8)

        val objectLength = objectBytes.size

        val buffer = ByteBuffer.allocate(4 + objectLength)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(objectLength)
        buffer.put(objectBytes)
        buffer.flip()
        return buffer
    }

    class ObjectReaderState {
        private var lengthBuffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        var objectBuffer: ByteBuffer? = null
        internal var expectedLength: Int = -1
            private set
        var isLengthRead: Boolean = false
            private set

        fun readLengthFromBuffer(dataBuffer: ByteBuffer): Boolean {
            if (isLengthRead) return true

            while (dataBuffer.hasRemaining() && lengthBuffer.hasRemaining()) {
                lengthBuffer.put(dataBuffer.get())
            }

            if (!lengthBuffer.hasRemaining()) {
                lengthBuffer.flip()
                expectedLength = lengthBuffer.getInt()
                isLengthRead = true
                lengthBuffer.clear()
                if (expectedLength < 0 || expectedLength > 10 * 1024 * 1024) {
                    reset()
                    throw IOException("Invalid object length received: $expectedLength. Max allowed: ${10 * 1024 * 1024}")
                }
                objectBuffer = ByteBuffer.allocate(expectedLength)
                return true
            }
            return false
        }

        fun readObjectBytesFromBuffer(dataBuffer: ByteBuffer): Boolean {
            if (!isLengthRead || objectBuffer == null) {
                return false
            }
            val currentObjectBuffer = objectBuffer ?: return false

            while (dataBuffer.hasRemaining() && currentObjectBuffer.hasRemaining()) {
                currentObjectBuffer.put(dataBuffer.get())
            }
            return !currentObjectBuffer.hasRemaining()
        }

        inline fun <reified T : Any> deserializeObject(): T? {
            val currentObjectBuffer = objectBuffer
            if (!isLengthRead || currentObjectBuffer == null || currentObjectBuffer.hasRemaining()) {
                return null
            }
            currentObjectBuffer.flip()
            val objectBytes = ByteArray(currentObjectBuffer.remaining())
            currentObjectBuffer.get(objectBytes)

            return try {
                val serializer = serializer<T>()
                jsonFormat.decodeFromString(serializer, String(objectBytes, Charsets.UTF_8))
            } catch (e: Exception) {
                System.err.println("Deserialization error for type ${T::class.simpleName} from JSON: ${e.message}")
                System.err.println(
                    "Problematic JSON string: ${
                        String(
                            objectBytes,
                            Charsets.UTF_8
                        )
                    }"
                )
                e.printStackTrace()
                null
            } finally {
                reset()
            }
        }

        fun reset() {
            lengthBuffer.clear()
            objectBuffer = null
            expectedLength = -1
            isLengthRead = false
        }
    }
}