package org.example.core

import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

object SerializationUtils {

    fun <T : Serializable> objectToByteBuffer(obj: T): ByteBuffer {
        val baos = ByteArrayOutputStream()
        ObjectOutputStream(baos).use { oos ->
            oos.writeObject(obj)
        }
        val objectBytes = baos.toByteArray()
        val objectLength = objectBytes.size

        // Выделяем буфер: 4 байта для длины + длина самого объекта
        val buffer = ByteBuffer.allocate(4 + objectLength)
        buffer.order(ByteOrder.BIG_ENDIAN) // Для консистентности порядка байт (не обязательно, но хорошо)
        buffer.putInt(objectLength)        // Записываем длину
        buffer.put(objectBytes)            // Записываем сам объект
        buffer.flip()                      // Готовим буфер к чтению (для отправки)
        return buffer
    }

    // Этот класс будет использоваться для хранения состояния чтения для каждого клиента
    class ObjectReaderState {
        private var lengthBuffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        private var objectBuffer: ByteBuffer? = null
        var expectedLength: Int = -1
            private set
        var isLengthRead: Boolean = false
            private set

        // Возвращает true, если длина полностью прочитана из предоставленного dataBuffer
        fun readLengthFromBuffer(dataBuffer: ByteBuffer): Boolean {
            if (isLengthRead) return true

            while (dataBuffer.hasRemaining() && lengthBuffer.hasRemaining()) {
                lengthBuffer.put(dataBuffer.get())
            }

            if (!lengthBuffer.hasRemaining()) {
                lengthBuffer.flip()
                expectedLength = lengthBuffer.getInt()
                isLengthRead = true
                lengthBuffer.clear() // Сброс для следующего сообщения
                if (expectedLength < 0 || expectedLength > 10 * 1024 * 1024) { // Защита от слишком больших объектов
                    throw IOException("Invalid object length received: $expectedLength")
                }
                objectBuffer = ByteBuffer.allocate(expectedLength)
                return true
            }
            return false // Длина еще не прочитана полностью
        }


        fun readObjectBytesFromBuffer(dataBuffer: ByteBuffer): Boolean {
            if (!isLengthRead || objectBuffer == null) {
                // throw IllegalStateException("Length not read or objectBuffer not initialized")
                return false // Не можем читать объект, если не знаем его длину
            }

            while (dataBuffer.hasRemaining() && objectBuffer!!.hasRemaining()) {
                objectBuffer!!.put(dataBuffer.get())
            }
            return !objectBuffer!!.hasRemaining() // true, если все байты объекта прочитаны
        }


        fun <T : Serializable> deserializeObject(): T? {
            if (!isLengthRead || objectBuffer == null || objectBuffer!!.hasRemaining()) {
                // throw IllegalStateException("Object not fully read")
                return null // Еще не все данные объекта прочитаны или ошибка
            }
            objectBuffer!!.flip()
            val bais = ByteArrayInputStream(objectBuffer!!.array(), 0, objectBuffer!!.limit())
            return try {
                ObjectInputStream(bais).use { ois ->
                    ois.readObject() as T
                }
            } catch (e: Exception) {
                // Логирование ошибки десериализации
                e.printStackTrace()
                null
            } finally {
                // Сброс состояния для следующего объекта
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