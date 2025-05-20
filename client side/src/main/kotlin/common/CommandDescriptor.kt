package common

@kotlinx.serialization.Serializable
enum class ArgumentType {
    STRING, INTEGER, DOUBLE, NO_ARGS
}

@kotlinx.serialization.Serializable
data class CommandArgument(
    val name: String,
    val type: ArgumentType,
    val isOptional: Boolean = false,
    val description: String? = null
)

@kotlinx.serialization.Serializable
data class CommandDescriptor(
    val name: String,
    val description: String,
    val arguments: List<CommandArgument> = emptyList(),
    val requiresVehicleObject: Boolean = false
)
