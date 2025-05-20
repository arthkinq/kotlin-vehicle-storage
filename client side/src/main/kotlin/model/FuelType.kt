package model

@kotlinx.serialization.Serializable
enum class FuelType () {
    KEROSENE,
    DIESEL,
    ALCOHOL,
    MANPOWER,
    NUCLEAR
}