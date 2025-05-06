import org.example.IO.IOManager
import org.example.core.Request
import org.example.core.Response
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.InetSocketAddress
import java.nio.channels.SocketChannel

class ApiClient(
    private val ioManager: IOManager
) {
    private val newCommandsList = mutableListOf<String>()

    private fun connectToServer(): SocketChannel {
        return try {
            val clientSocket = SocketChannel.open()
            clientSocket.socket().connect(InetSocketAddress("localhost", 8888))
            clientSocket
        } catch (e: RuntimeException) {
            ioManager.outputLine("Error connecting to server!")
            throw e
        }
    }

    fun outputStreamHandler(request: Request) {
        try {
            val clientSocket = connectToServer()
            if (clientSocket.isConnected) {
                val objectOutputStream = ObjectOutputStream(clientSocket.socket().getOutputStream())
                objectOutputStream.writeObject(request)
                inputStreamHandler(clientSocket)
            }
        } catch (e: Exception) {
            ioManager.outputLine("Output error!")
        }
    }

    private fun inputStreamHandler(clientSocket: SocketChannel) {
        val objectInputStream = ObjectInputStream(clientSocket.socket().getInputStream())
        val response = objectInputStream.readObject() as Response
        newCommandsList.addAll(response.newCommandsList)
        ioManager.outputLine(response.responseText)
        clientSocket.close()
    }

    fun returnNewCommands(): MutableList<String> {
        return newCommandsList
    }

    fun resetNewCommands() {
        newCommandsList.clear()
    }


}