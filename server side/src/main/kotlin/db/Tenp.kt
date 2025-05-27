
// 1. package corepackage core // Объявление пакета, к которому принадлежит класс
// 2. import db.VehicleDAO
// 3. import model.Vehicle// 4. import java.util.concurrent.ConcurrentHashMap
// 5. import java.util.concurrent.locks.ReentrantReadWriteLock// 6. import kotlin.concurrent.read
// 7. import kotlin.concurrent.write// 8. import java.util.logging.Logger
import db.VehicleDAO // Импорт интерфейса/класса для доступа к данным Vehicleimport model.Vehicle // Импорт модели данных Vehicle
import model.Vehicle
import java.util.concurrent.ConcurrentHashMap // Импорт потокобезопасной реализации Map
import java.util.concurrent.locks.ReentrantReadWriteLock // Импорт блокировки для чтения/записи (не используется в текущей версии, но импорт есть)
import kotlin.concurrent.read // Импорт расширения для ReentrantReadWriteLock (не используется)
import kotlin.concurrent.write // Импорт расширения для ReentrantReadWriteLock (не используется)
import java.util.logging.Logger // Импорт стандартного Java логгера

// 9. class VehicleService(private val vehicleDAO: VehicleDAO) {
class VehicleService(private val vehicleDAO: VehicleDAO) { // Объявление класса VehicleService.
    // Принимает в конструкторе экземпляр VehicleDAO (инъекция зависимости).
    // vehicleDAO используется для взаимодействия с базой данных.
    // 10. private val logger = Logger.getLogger(VehicleService::class.java.name)
    private val logger = Logger.getLogger(VehicleService::class.java.name) // Инициализация логгера для этого класса.

    // 11. private val vehiclesCache: ConcurrentHashMap<Int, Vehicle> = ConcurrentHashMap()
    private val vehiclesCache: ConcurrentHashMap<Int, Vehicle> = ConcurrentHashMap() // Инициализация кэша в памяти.

    // ConcurrentHashMap потокобезопасен для большинства операций (get, put, remove, size, итерации).
    // Ключ - Int (id транспортного средства), значение - объект Vehicle.
    // 12. private val cacheLock = Any() // Object for synchronized blocks
    private val cacheLock = Any() // Создание простого объекта, который будет использоваться в качестве монитора (замка)
    // для блоков synchronized, обеспечивающих синхронизацию для составных операций с кэшем.

    // 13. init {
    init { // Блок инициализации, выполняется при создании экземпляра VehicleService.
        // 14. loadAllFromDB()
        loadAllFromDB() // Вызов метода для загрузки всех транспортных средств из БД в кэш.
        // 15. }
    }

    // 16. private fun loadAllFromDB() {
    private fun loadAllFromDB() { // Приватный метод для загрузки данных из БД в кэш.
        // 17. synchronized(cacheLock) {
        synchronized(cacheLock) { // Блок синхронизации по объекту cacheLock. Гарантирует, что только один поток
            // одновременно может выполнять код внутри этого блока. Это нужно для атомарности
            // операций очистки и заполнения кэша.
            // 18. vehiclesCache.clear()
            vehiclesCache.clear() // Очистка текущего содержимого кэша.
            // 19. val vehiclesFromDB = vehicleDAO.getAllVehicles()
            val vehiclesFromDB = vehicleDAO.getAllVehicles() // Получение всех Vehicle из базы данных через DAO.
            // 20. vehiclesFromDB.forEach { vehiclesCache[it.id] = it }
            vehiclesFromDB.forEach { vehiclesCache[it.id] = it } // Добавление

            // ConcurrentHashMap.put (неявный вызов через vehiclesCache[key] = value) потокобезопасен.
            // 21. logger.info("Loaded ${vehiclesCache.size} vehicles into memory cache.")
            logger.info("Loaded ${vehiclesCache.size} vehicles into memory cache.") // Логирование количества загруженных объектов.
            // 22. }
        }
        // 23. }
    }

    // 24. fun addVehicle(vehicle: Vehicle, userId: Int): Vehicle? {
    fun addVehicle(vehicle: Vehicle, userId: Int): Vehicle? { // Публичный метод для добавления нового Vehicle.
        // Принимает объект Vehicle и ID пользователя, добавляющего его.
        // Возвращает добавленный Vehicle (с присвоенным ID из БД) или null в случае ошибки.
        // 25. val newVehicleWithId = vehicle.copy(creationDate = System.currentTimeMillis())
        val newVehicleWithId =
            vehicle.copy(creationDate = System.currentTimeMillis()) // Создание копии входного объекта Vehicle,
        // но с установленной текущей датой создания.
        // Предполагается, что ID будет присвоен БД.
        // userId уже должен быть в vehicle или будет установлен в DAO.
        // 26. val addedVehicle = vehicleDAO.addVehicle(newVehicleWithId, userId)
        val addedVehicle =
            vehicleDAO.addVehicle(newVehicleWithId, userId) // Вызов метода DAO для добавления Vehicle в БД.
        // DAO должен вернуть объект с присвоенным ID.
        // 27. if (addedVehicle != null) {
        if (addedVehicle != null) { // Если добавление в БД прошло успешно (DAO вернул не null).
            // 28. synchronized(cacheLock) {
            synchronized(cacheLock) { // Синхронизация доступа к кэшу. Хотя put в ConcurrentHashMap потокобезопасен,
                // здесь это может быть для консистентности с другими операциями,
                // или если бы тут было несколько операций с кэшем.
                // 29. vehiclesCache[addedVehicle.id] = addedVehicle
                vehiclesCache[addedVehicle.id] = addedVehicle // Добавление успешно сохраненного Vehicle в кэш.
                // 30. }
            }
            // 31. logger.info("Vehicle ${addedVehicle.id} added to cache.")
            logger.info("Vehicle ${addedVehicle.id} added to cache.") // Логирование.
            // 32. return addedVehicle
            return addedVehicle // Возврат добавленного объекта.
            // 33. }
        }
        // 34. logger.warning("Failed to add vehicle to DB, not adding to cache.")
        logger.warning("Failed to add vehicle to DB, not adding to cache.") // Логирование, если добавление в БД не удалось.
        // 35. return null
        return null // Возврат null в случае ошибки.
        // 36. }
    }

    fun updateVehicleById(vehicle: Vehicle, userId: Int): Boolean {
        val vehicleToUpdate = vehicleDAO.getVehicleById(vehicle.id)
        if (vehicleToUpdate == null) {
            return false
        }
        if (vehicleToUpdate.id != vehicle.id) {
            logger.warning("User $userId to update vehicle ${vehicle.id} owned by ${vehicleToUpdate.userId}")
            return false
        }
        val success = vehicleDAO.updateVehicle(vehicle.copy(userId = vehicleToUpdate.userId))
        if (success) {
            synchronized(cacheLock) {
                vehiclesCache[vehicle.id] = vehicle.copy(userId = vehicleToUpdate.userId)
            }
            logger.info("Vehicle ${vehicle.id} updated in cache.")
            return true
        }
        return false
    }

    // 54. fun removeVehicle(vehicleId: Int, requestingUserId: Int): Boolean {
    fun removeVehicle(vehicleId: Int, requestingUserId: Int): Boolean { // Метод для удаления Vehicle.
        // Принимает ID удаляемого Vehicle и ID пользователя.
        // Возвращает true, если удаление успешно, иначе false.
        // 55. val vehicleToRemove = vehiclesCache[vehicleId]
        val vehicleToRemove = vehiclesCache[vehicleId] // Получаем Vehicle из кэша для проверки владельца.
        // 56. if (vehicleToRemove != null && vehicleToRemove.userId != requestingUserId) {
        if (vehicleToRemove != null && vehicleToRemove.userId != requestingUserId) { // Если Vehicle есть в кэше и пользователь не владелец.
            // 57. logger.warning("User $requestingUserId attempted to remove vehicle $vehicleId owned by ${vehicleToRemove.userId}")
            logger.warning("User $requestingUserId attempted to remove vehicle $vehicleId owned by ${vehicleToRemove.userId}") // Логирование.
            // 58. return false
            return false // Отказ в удалении.
            // 59. }
        }

        // 60. val success = vehicleDAO.deleteVehicle(vehicleId, requestingUserId)
        val success =
            vehicleDAO.deleteVehicle(vehicleId, requestingUserId) // Вызов метода DAO для удаления Vehicle из БД.
        // DAO должен сам проверить права на удаление или это сделано выше.
        // 61. if
        if (success) {
            if (success) { // Если удаление из БД прошло успешно.
                // 62. synchronized(cacheLock) {
                synchronized(cacheLock) { // Синхронизация для удаления из кэша.
                    // 63. vehiclesCache.remove(vehicleId)
                    vehiclesCache.remove(vehicleId) // Удаляем Vehicle из кэша.
                    // ConcurrentHashMap.remove потокобезопасен.
                    // 64. }
                }
                // 65. logger.info("Vehicle $vehicleId removed from cache.")
                logger.info("Vehicle $vehicleId removed from cache.") // Логирование.
                // 66. return true
                return true // Возвращаем true.
                // 67. }
            }
            // 68. logger.warning("Failed to remove vehicle $vehicleId from DB or not owned by user, not removing from cache (or already removed).")
            logger.warning("Failed to remove vehicle $vehicleId from DB or not owned by user, not removing from cache (or already removed).") // Логирование, если удаление из БД не удалось.
            // 69. return false
            return false // Возвращаем false.
            // 70. }
        }

        // 71. fun getVehicleById(id: Int): Vehicle? {
        fun getVehicleById(id: Int): Vehicle? { // Метод для получения Vehicle по ID.
            // 72. return vehiclesCache[id]
            return vehiclesCache[id] // Простое получение из ConcurrentHashMap, которое потокобезопасно для чтения.
            // 73. }
        }

        // 74. fun getAllVehicles(): List<Vehicle> {
        fun getAllVehicles(): List<Vehicle> { // Метод для получения всех Vehicle из кэша.
            // 75. synchronized(cacheLock) {
            synchronized(cacheLock) { // Синхронизация для получения консистентного "снимка" кэша на момент вызова.
                // Хотя итерация по .values() ConcurrentHashMap потокобезопасна (не бросит ConcurrentModificationException),
                // синхронизация здесь гарантирует, что мы получим список, который не изменится во время его копирования и сортировки.
                // 76. return vehiclesCache.values.toList().sortedBy { it.id }
                return vehiclesCache.values.toList()
                    .sortedBy { it.id } // Получаем все значения из кэша, преобразуем в список и сортируем по ID.
                // 77. }
            }
            // 78. }
        }

        // 79. fun getMin(): Vehicle? {
        fun getMin(): Vehicle? { // Метод для получения Vehicle с минимальным значением (по естественному порядку, т.е. Comparable).
            // 80. synchronized(cacheLock) {
            synchronized(cacheLock) { // Синхронизация для консистентного поиска минимума.
                // 81. return vehiclesCache.values.minOrNull()
                return vehiclesCache.values.minOrNull() // Используем стандартную Kotlin функцию для коллекций.
                // 82. }
            }
            // 83. }
        }

        // 84. fun getMax(): Vehicle? {
        fun getMax(): Vehicle? { // Метод для получения Vehicle с максимальным значением.
            // 85. synchronized(cacheLock) {
            synchronized(cacheLock) { // Синхронизация.
                // 86. return vehiclesCache.values.maxOrNull()
                return vehiclesCache.values.maxOrNull()
                // 87. }
            }
            // 88. }
        }

        // 89. fun getSize(): Int {
        fun getSize(): Int { // Метод для получения текущего размера кэша.
            // 90. return vehiclesCache.size
            return vehiclesCache.size // ConcurrentHashMap.size() потокобезопасен.
            // 91. }
        }

        // 92. fun clearUserVehicles(userId: Int) {
        fun clearUserVehicles(userId: Int) { // Метод для удаления всех Vehicle, принадлежащих определенному пользователю.
            // 93. val vehiclesToRemove = mutableListOf<Int>()
            val vehiclesToRemove =
                mutableListOf<Int>() // Список ID для удаления из кэша после успешного удаления из БД.
            // 94. synchronized(cacheLock) {
            synchronized(cacheLock) { // Синхронизация на время всей операции (фильтрация, удаление из БД, подготовка к удалению из кэша).
                // 95. vehiclesCache.values.filter { it.userId == userId }.forEach { vehicle ->
                vehiclesCache.values.filter { it.userId == userId } // Фил

                    .forEach { vehicle -> // Для каждого найденного Vehicle.
                        // 96. if (vehicleDAO.deleteVehicle(vehicle.id, userId)) {
                        if (vehicleDAO.deleteVehicle(vehicle.id, userId)) { // Пытаемся удалить из БД.
                            // 97. vehiclesToRemove.add(vehicle.id)
                            vehiclesToRemove.add(vehicle.id) // Если из БД удалено успешно, добавляем ID в список для удаления из кэша.
                            // 98. }
                        }
                        // 99. }
                    }
                // 100. vehiclesToRemove.forEach { vehiclesCache.remove(it) }
                vehiclesToRemove.forEach { vehiclesCache.remove(it) } // Удаляем из кэша все Vehicle, которые были успешно удалены из БД.
                // 101. }
            }
            // 102. logger.info("Cleared ${vehiclesToRemove.size} vehicles for user $userId from cache and DB.")
            logger.info("Cleared ${vehiclesToRemove.size} vehicles for user $userId from cache and DB.") // Логирование.
            // 103. }
        }
}

