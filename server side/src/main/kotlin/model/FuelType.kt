package model

import java.util.*

@kotlinx.serialization.Serializable
enum class FuelType () {
    KEROSENE,
    DIESEL,
    ALCOHOL,
    MANPOWER,
    NUCLEAR;
    override fun toString(): String {

        return name.lowercase().replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }

    }
}