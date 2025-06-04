package common

import kotlinx.serialization.Serializable
import model.Vehicle

@Serializable
data class Response(
    var responseText: String = "Success",
    val commandDescriptors: MutableList<CommandDescriptor> = mutableListOf(),
    val vehicles: List<Vehicle>? = null
) {

    fun addCommandDescriptors(descriptors: List<CommandDescriptor>) {
        commandDescriptors.addAll(descriptors)
    }

    fun clearCommandDescriptors() {
        commandDescriptors.clear()
    }
}
