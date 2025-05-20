package common

import kotlinx.serialization.Serializable

@Serializable
data class Response(
    var responseText: String = "Success",
    val commandDescriptors: MutableList<CommandDescriptor> = mutableListOf()
) {

    fun addCommandDescriptors(descriptors: List<CommandDescriptor>) {
        commandDescriptors.addAll(descriptors)
    }

    fun clearCommandDescriptors() {
        commandDescriptors.clear()
    }
}
