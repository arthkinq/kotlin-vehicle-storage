package db

import model.User
import utils.PasswordHasher.hashPassword
import java.sql.SQLException
import java.util.logging.Logger


/* добавление новых пользователей*/
/* DAO - паттерн проектирования, абстрактный интерфейс к типу данных из бд*/
class UserDAO {
    private val logger = Logger.getLogger(this.javaClass.name)
    /* Функция возвращает объект User или null если объект не добавлен*/
    fun addUser(username: String, password: String) : User? {
        val hashedPassword = hashPassword(password)
        val sql = "INSERT INTO users (username, password_hash) VALUES (?, ?)"
        try {
            DatabaseManager.getConnection().use { connection ->
                connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS).use { statement ->
                    statement.setString(1, username)
                    statement.setString(2, hashedPassword)
                    statement.executeUpdate()
                    statement.generatedKeys.use { generatedKeys ->
                        if(generatedKeys.next()) {
                            val id = generatedKeys.getInt("id")
                            logger.info("User $username $id has been added to database.")
                            return User(id, username, hashedPassword)
                        } else {
                            logger.warning("Creating user failed. $username $password")
                            return null
                        }
                    }
                }
            }
        } catch (e: SQLException) {
            if(e.sqlState == "23505") {
                logger.warning("User $username $password already exists.")
            } else {
                logger.warning("Error adding user: $e")
            }
            return null
        }
    }
    fun findUserByUsername(username: String): User? {
        val sql = "SELECT * FROM users WHERE username = ?"
        try {
            DatabaseManager.getConnection().use { connection ->
                connection.prepareStatement(sql).use { statement ->
                    statement.setString(1, username)
                    statement.executeQuery().use { resultSet ->
                        if(resultSet.next()) {
                            return User(resultSet.getInt("id"), username, resultSet.getString("password_hash"))
                        }
                    }
                }
            }
        } catch (e: SQLException) {
            logger.warning("Error getting user $username: $e")
        }
        return null
    }
    fun verifiPassword(user: User, password: String): Boolean {
        return user.passwordHash ==  hashPassword(password)
    }
}