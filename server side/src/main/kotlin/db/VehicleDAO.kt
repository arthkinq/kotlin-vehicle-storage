package db

import model.*
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.util.logging.Level
import java.util.logging.Logger

/* Выполняет запросы, связанные с вехикле, возвращает список вехикле/вехикле*/
class VehicleDAO {

    private val logger = Logger.getLogger(VehicleDAO::class.java.name)

    /* принимает объект ResultSet
     и преобразует данные из этой строки в объект типа Vehicle.*/
    private fun mapRowToVehicle(rs: ResultSet): Vehicle {
        return Vehicle(
            id = rs.getInt("id"),
            name = rs.getString("name"),
            coordinates = Coordinates(
                x = rs.getInt("coordinates_x"),
                y = rs.getFloat("coordinates_y")
            ),
            creationDate = rs.getLong("creation_date"),
            enginePower = rs.getDouble("engine_power"),
            distanceTravelled = rs.getObject("distance_travelled") as Double?,
            type = rs.getString("type")?.let { VehicleType.valueOf(it) },
            fuelType = rs.getString("fuel_type")?.let { FuelType.valueOf(it) },
            userId = rs.getInt("user_id")
        )
    }

    /* Принимает объект Vehicle и ID пользователя (userId), который добавляет этот Vehicle.*/
    fun addVehicle(vehicle: Vehicle, userId: Int): Vehicle? {
        val sql = """
            INSERT INTO vehicles (name, coordinates_x, coordinates_y, creation_date, engine_power,
                                  distance_travelled, type, fuel_type, user_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        try {
            DatabaseManager.getConnection().use { conn ->
                conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { pstmt ->
                    pstmt.setString(1, vehicle.name)
                    pstmt.setInt(2, vehicle.coordinates.x)
                    pstmt.setFloat(3, vehicle.coordinates.y)
                    pstmt.setLong(4, vehicle.creationDate)
                    pstmt.setDouble(5, vehicle.enginePower)

                    if (vehicle.distanceTravelled != null) {
                        pstmt.setDouble(6, vehicle.distanceTravelled!!)
                    } else {
                        pstmt.setNull(6, java.sql.Types.DOUBLE)
                    }
                    pstmt.setString(7, vehicle.type?.name)
                    pstmt.setString(8, vehicle.fuelType?.name)
                    pstmt.setInt(9, userId)

                    val affectedRows = pstmt.executeUpdate()
                    if (affectedRows == 0) {
                        logger.warning("Creating vehicle failed, no rows affected.")
                        return null
                    }
                    pstmt.generatedKeys.use { generatedKeys ->
                        if (generatedKeys.next()) {
                            val newId = generatedKeys.getInt(1)
                            logger.info("Vehicle '${vehicle.name}' added with ID $newId for user $userId.")
                            return vehicle.copy(id = newId, userId = userId)
                        } else {
                            logger.warning("Creating vehicle failed, no ID obtained for '${vehicle.name}'.")
                            return null
                        }
                    }
                }
            }
        } catch (e: SQLException) {
            logger.log(Level.SEVERE, "Error adding vehicle '${vehicle.name}': ${e.message}", e)
            return null
        }
    }

    fun updateVehicle(vehicle: Vehicle): Boolean {
        /* Условие WHERE id = ? AND user_id = ? гарантирует, что обновляется конкретный объект Vehicle
         и что текущий пользователь (vehicle.userId) является его владельцем.*/
        val sql = """
            UPDATE vehicles SET name = ?, coordinates_x = ?, coordinates_y = ?,
                               engine_power = ?, distance_travelled = ?, type = ?, fuel_type = ?
            WHERE id = ? AND user_id = ? 
        """.trimIndent()
        try {
            DatabaseManager.getConnection().use { conn ->
                conn.prepareStatement(sql).use { pstmt ->
                    pstmt.setString(1, vehicle.name)
                    pstmt.setInt(2, vehicle.coordinates.x)
                    pstmt.setFloat(3, vehicle.coordinates.y)
                    pstmt.setDouble(4, vehicle.enginePower)
                    if (vehicle.distanceTravelled != null) {
                        pstmt.setDouble(5, vehicle.distanceTravelled!!)
                    } else {
                        pstmt.setNull(5, java.sql.Types.DOUBLE)
                    }
                    pstmt.setString(6, vehicle.type?.name)
                    pstmt.setString(7, vehicle.fuelType?.name)
                    pstmt.setInt(8, vehicle.id)
                    pstmt.setInt(9, vehicle.userId)

                    val affectedRows = pstmt.executeUpdate()
                    if (affectedRows > 0) {
                        logger.info("Vehicle ID ${vehicle.id} updated.")
                        return true
                    }
                    logger.warning("Vehicle ID ${vehicle.id} not found or not owned by user ${vehicle.userId} for update.")
                    return false
                }
            }
        } catch (e: SQLException) {
            logger.log(Level.SEVERE, "Error updating vehicle ID ${vehicle.id}: ${e.message}", e)
            return false
        }
    }

    fun deleteVehicle(vehicleId: Int, userId: Int): Boolean {
        val sql = "DELETE FROM vehicles WHERE id = ? AND user_id = ?"
        try {
            DatabaseManager.getConnection().use { conn ->
                conn.prepareStatement(sql).use { pstmt ->
                    pstmt.setInt(1, vehicleId)
                    pstmt.setInt(2, userId)
                    val affectedRows = pstmt.executeUpdate()
                    if (affectedRows > 0) {
                        logger.info("Vehicle ID $vehicleId deleted by user $userId.")
                        return true
                    }
                    return false
                }
            }
        } catch (e: SQLException) {
            logger.log(Level.SEVERE, "Error deleting vehicle ID $vehicleId: ${e.message}", e)
            return false
        }
    }

    fun getVehicleById(vehicleId: Int): Vehicle? {
        val sql = "SELECT * FROM vehicles WHERE id = ?"
        try {
            DatabaseManager.getConnection().use { conn ->
                conn.prepareStatement(sql).use { pstmt ->
                    pstmt.setInt(1, vehicleId)
                    pstmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            return mapRowToVehicle(rs)
                        }
                    }
                }
            }
        } catch (e: SQLException) {
            logger.log(Level.SEVERE, "Error fetching vehicle ID $vehicleId: ${e.message}", e)
        }
        return null
    }

    fun getAllVehicles(): List<Vehicle> {
        val vehicles = mutableListOf<Vehicle>()
        val sql = """
        SELECT v.*, u.username, u.first_name, u.last_name 
        FROM vehicles v
        LEFT JOIN users u ON v.user_id = u.id
    """
        try {
            DatabaseManager.getConnection().use { conn ->
                conn.prepareStatement(sql).use { pstmt ->
                    pstmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            vehicles.add(mapRowToVehicle(rs))
                        }
                    }
                }
            }
        } catch (e: SQLException) {
            logger.log(Level.SEVERE, "Error fetching all vehicles: ${e.message}", e)
        }
        logger.info("Fetched ${vehicles.size} vehicles from database.")
        return vehicles
    }
}
   

