package model

import java.util.*

@kotlinx.serialization.Serializable
enum class VehicleType () {
    BOAT,
    BICYCLE,
    HOVERBOARD;
    override fun toString(): String {

        return name.lowercase().replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }

    }
}