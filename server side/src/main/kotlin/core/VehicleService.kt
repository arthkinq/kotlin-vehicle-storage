package core

import db.VehicleDAO
import model.Vehicle
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/* работает с vehicleDAO чтобы обновлять все в бд, а только потом в Tommy Cache*/

class VehicleService (private val vehicleDAO: VehicleDAO) {
    private val logger = Logger.getLogger(VehicleService::class.java.name)
    // Безопасен для тредсов
    private val vehiclesCache: ConcurrentHashMap<Int, Vehicle> = ConcurrentHashMap()
    // Заглушечка
    private val cacheLock = Any()
    init {
        loadDB()
    }
    fun loadDB() {
        /* ConcHashMap уже безопасен для одиночных операций, но не для составных*/
        synchronized(cacheLock) {
            vehiclesCache.clear()
            val vehiclesFromDB = vehicleDAO.getAllVehicles()
            vehiclesFromDB.forEach {
                vehiclesCache.put(it.id, it)
            }
            logger.info { "Loaded ${vehiclesCache.size} vehicles" }
        }
    }
    fun addVehicle(vehicle: Vehicle, userId: Int) : Vehicle? {
        val newVehicle = vehicle.copy(creationDate = System.currentTimeMillis())
        val addedVehicle = vehicleDAO.addVehicle(newVehicle, userId)
        if(addedVehicle != null) {
            synchronized(cacheLock) {
                vehicleDAO.updateVehicle(addedVehicle)
            }
            logger.info { "Vehicle $addedVehicle.id ${addedVehicle.id}" }
            return addedVehicle
        }
        logger.warning { "Failed to add vehicle ${newVehicle.name}" }
        return null
    }
    fun updateVehicleById(id: Int, vehicle: Vehicle, userId: Int?): Boolean {
        val vehicleToUpdate = vehicleDAO.getVehicleById(id)
        if (vehicleToUpdate == null) { return false}
        if(vehicleToUpdate.userId != userId){
            logger.warning("User $userId to update vehicle ${vehicle.id} owned by ${vehicleToUpdate.userId}" )
            return false
        }
        val success = vehicleDAO.updateVehicle(vehicle.copy(userId = vehicleToUpdate.userId))
        if(success) {
            synchronized(cacheLock) {
                vehiclesCache[vehicle.id] = vehicle.copy(userId = vehicleToUpdate.userId)
            }
            logger.info("Vehicle ${vehicle.id} updated in cache.")
            return true
        }
        logger.warning("Failed to update vehicle ${vehicle.id} in DB, not updating cache.")
        return false
    }
    fun removeVehicle(vehicleId: Int, userId: Int) : Boolean {
        val vehicleToRemove = vehicleDAO.getVehicleById(vehicleId)
        if (vehicleToRemove == null) { return false}
        if(vehicleToRemove.userId != userId){
            logger.warning("User $userId attempted to remove vehicle $vehicleId owned by ${vehicleToRemove.userId}")
            return false
        }
        val success = vehicleDAO.deleteVehicle(vehicleId, userId)
        if(success) {
            synchronized(cacheLock) {
                vehiclesCache.remove(vehicleId)
            }
            logger.info( "Vehicle $vehicleId deleted in cache." )
            return true
        }
        logger.warning("Failed to remove vehicle $vehicleId in DB, not deleting cache.")
        return false
    }
    fun getVehicleById(vehicleId: Int): Vehicle? {
        /*val success = vehicleDAO.getVehicleById(vehicleId)
        if(success != null) { return success }*/
        return vehiclesCache.get(vehicleId)
    }
    fun getAll(): List<Vehicle> {
        synchronized(cacheLock) {
            return vehiclesCache.values.toList().sortedBy { it.id }
        }
    }
    fun clearUserVehicles(userId: Int) {
        val vehiclesToRemove =  mutableListOf<Int>()
        synchronized(cacheLock) {
            vehiclesCache.values.filter { it.userId == userId }

                .forEach { vehicle ->
                    if (vehicleDAO.deleteVehicle(vehicle.id, userId)) {
                        vehiclesToRemove.add(vehicle.id)
                    }
                }

            vehiclesToRemove.forEach { vehiclesCache.remove(it) }
    }
        logger.info("Cleared ${vehiclesToRemove.size} vehicles for user $userId from cache and DB.")
    }
    fun size(): Int {
        return vehiclesCache.size
    }
    fun getMax(characteristic: String): Vehicle? {
        synchronized(cacheLock) {
            if (vehiclesCache.isEmpty()) return null

            return when (characteristic) {
                "id" -> vehiclesCache.values.maxByOrNull { it.id }
                "name" -> vehiclesCache.values.maxByOrNull { it.name }
                "coordinates" -> vehiclesCache.values.maxByOrNull { it.coordinates.toString() }
                "enginePower" -> vehiclesCache.values.maxByOrNull { it.enginePower }
                "distanceTravelled" -> vehiclesCache.values.maxByOrNull { it.distanceTravelled ?: Double.MIN_VALUE }
                "type" -> vehiclesCache.values.maxByOrNull { it.type?.name ?: "" }
                "fuelType" -> vehiclesCache.values.maxByOrNull { it.fuelType?.name ?: "" }
                else -> throw IllegalArgumentException("Unknown characteristic: $characteristic")
            }
        }
    }
    fun getMin(characteristic: String): Vehicle? {
        synchronized(cacheLock) {
            if (vehiclesCache.isEmpty()) return null

            return when (characteristic) {
                "id" -> vehiclesCache.values.minByOrNull { it.id }
                "name" -> vehiclesCache.values.minByOrNull { it.name }
                "coordinates" -> vehiclesCache.values.minByOrNull { it.coordinates.toString() }
                "enginePower" -> vehiclesCache.values.minByOrNull { it.enginePower }
                "distanceTravelled" -> vehiclesCache.values.minByOrNull { it.distanceTravelled ?: Double.MIN_VALUE }
                "type" -> vehiclesCache.values.minByOrNull { it.type?.name ?: "" }
                "fuelType" -> vehiclesCache.values.minByOrNull { it.fuelType?.name ?: "" }
                else -> throw IllegalArgumentException("Unknown characteristic: $characteristic")
            }
        }
    }
    fun filterByCharacteristic(characteristic: String, arg: String): List<Vehicle> {
        synchronized(cacheLock) {
            return when (characteristic) {
                "id" -> vehiclesCache.values.filter { it.id == arg.toIntOrNull() }
                "name" -> vehiclesCache.values.filter { it.name == arg }
                "coordinates" -> vehiclesCache.values.filter { it.coordinates.toString() == arg }
                "enginePower" -> vehiclesCache.values.filter { it.enginePower == arg.toDoubleOrNull() }
                "distanceTravelled" -> vehiclesCache.values.filter { it.distanceTravelled == arg.toDoubleOrNull() }
                "type" -> vehiclesCache.values.filter { it.type?.name.equals(arg, ignoreCase = true) }
                "fuelType" -> vehiclesCache.values.filter { it.fuelType?.name.equals(arg, ignoreCase = true) }
                else -> throw IllegalArgumentException("Unknown characteristic: $characteristic")
            }
        }
    }

    fun findByCharacteristic(characteristic: String, arg: String): Vehicle? {
        synchronized(cacheLock) {
            return when (characteristic) {
                "id" -> vehiclesCache.values.find { it.id == arg.toIntOrNull() }
                "name" -> vehiclesCache.values.find { it.name == arg }
                "coordinates" -> vehiclesCache.values.find { it.coordinates.toString() == arg }
                "enginePower" -> vehiclesCache.values.find { it.enginePower == arg.toDoubleOrNull() }
                "distanceTravelled" -> vehiclesCache.values.find { it.distanceTravelled == arg.toDoubleOrNull() }
                "type" -> vehiclesCache.values.find { it.type?.name.equals(arg, ignoreCase = true) }
                "fuelType" -> vehiclesCache.values.find { it.fuelType?.name.equals(arg, ignoreCase = true) }
                else -> throw IllegalArgumentException("Unknown characteristic: $characteristic")
            }
        }
    }
    fun deleteByNumber(number: Int) {
        synchronized(cacheLock) {
            if (vehiclesCache.isEmpty() || number < 0 || number >= vehiclesCache.size) {
                return // Невалидный индекс
            }

            val sortedVehicles = vehiclesCache.values.sortedBy { it.id }
            val vehicleToDelete = sortedVehicles[number]
            vehiclesCache.remove(vehicleToDelete.id)
        }
    }
    fun isEmpty() : Boolean {
        synchronized(cacheLock) {
            return vehiclesCache.isEmpty()
        }
    }

}