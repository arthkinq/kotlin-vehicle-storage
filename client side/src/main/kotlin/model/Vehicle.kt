package model

@kotlinx.serialization.Serializable
data class Vehicle(
    val id: Int,
    var name: String,
    var coordinates: Coordinates,
    val creationDate: Long,
    var enginePower: Double,
    var distanceTravelled: Double?,
    var type: VehicleType?,
    var fuelType: FuelType?,
    var userId: Int
) : Comparable<Vehicle>{
    init {
        require(id >= 0) { "ID must be positive" }
        require(name.isNotEmpty()) { "Name cannot be empty" }
        require(enginePower > 0) { "Engine power must be positive" }
        distanceTravelled?.let { require(it > 0) { "Distance must be positive if provided" } }
    }

    override fun compareTo(other: Vehicle): Int {
        return this.id.compareTo(other.id)
    }

    override fun toString(): String {
        return "Vehicle(id=$id, name='$name', coordinates=$coordinates, creationDate=$creationDate, " +
                "enginePower=$enginePower, distanceTravelled=$distanceTravelled, type=$type, fuelType=$fuelType)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Vehicle) return false
        return this.id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
    fun getCoordinateX() : Int {
        return coordinates.x
    }
    fun getCoordinateY(): Float {
        return coordinates.y
    }

}