 package common

 import kotlinx.serialization.Serializable
 import model.Vehicle

 @Serializable
 data class Request(
     val body: List<String>,
     var vehicle: Vehicle? = null,
 )