package db

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.logging.Logger

/* Будет хранить информацимю о БД, менять ее, создавать, хранить юзеров */
object DatabaseManager {
//    private const val DB_HOST = "pg"
//    private const val DB_NAME = "studs"
//    private const val DB_USER = "s476011"
//    private const val DB_PASSWORD = "Aqe0lCiTfTkfwHNt"
//    private const val DB_PORT = 5432

    private const val DB_HOST = "localhost"
    private const val DB_NAME = "proga"
    private const val DB_USER = "postgres"
    private const val DB_PASSWORD = "test"
    private const val DB_PORT = 5432

    private val logger = Logger.getLogger(DatabaseManager::class.java.name)
    private const val DB_URL = "jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME"
    init {
        try {
            Class.forName("org.postgresql.Driver")
            initializeDatabase()
            logger.info("PostgreSQL database initialized!")
        } catch (e: ClassNotFoundException) {
            logger.info("Driver not found! Error: ${e.message}")
            throw RuntimeException("Driver not found! Error: ${e.message}")
        } catch (e: SQLException) {
            logger.info("Database not initialized! Error: ${e.message}")
            throw RuntimeException("Database not initialized! Error: ${e.message}")
        }
    }
    fun getConnection(): Connection {
        try {
            return DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)
        } catch (e: SQLException) {
            logger.info("Database connection failed! Error: ${e.message}")
            throw SQLException("Database connection failed! Error: ${e.message}")
        }
    }
    private fun initializeDatabase() {
        /* try с параметрами от котлина */
        getConnection().use { conn ->
            /* connection с try с параметрами */
            conn.createStatement().use { stmt ->
                /* statment с параметрами и траем */
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS users (
                        id SERIAL PRIMARY KEY,
                        username VARCHAR(255) UNIQUE NOT NULL,
                        password_hash VARCHAR(128) NOT NULL
                    )
                """.trimIndent())
                logger.info("Table 'users' checked/created.")

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS vehicles (
                        id SERIAL PRIMARY KEY,
                        name VARCHAR(255) NOT NULL,
                        coordinates_x INTEGER NOT NULL,
                        coordinates_y REAL NOT NULL,
                        creation_date BIGINT NOT NULL,
                        engine_power DOUBLE PRECISION NOT NULL CHECK (engine_power > 0),
                        distance_travelled DOUBLE PRECISION CHECK (distance_travelled IS NULL OR distance_travelled > 0),
                        type VARCHAR(50),
                        fuel_type VARCHAR(50),
                        user_id INTEGER NOT NULL,
                        CONSTRAINT fk_user
                            FOREIGN KEY(user_id)
                            REFERENCES users(id)
                            ON DELETE CASCADE
                    )
                """.trimIndent())
                logger.info("Table 'vehicles' checked/created.")
            }
        }
        logger.info("Database schema initialization complete.")
    }

}