# Vehicle Manager (Client-Server Application)

A multi-user, TCP-based console application for managing a collection of vehicle objects stored in a PostgreSQL database. This project was developed as part of the university curriculum at ITMO University, demonstrating core backend development principles.

## Key Features

*   **Multi-User Environment:** Supports multiple clients connecting to a central server simultaneously.
*   **User Authentication:** Secure user registration and login system with password hashing.
*   **Rich Command Set:** Server-side logic processes 15+ commands for full CRUD (Create, Read, Update, Delete) functionality, including complex sorting and filtering.
*   **Reliable Data Transfer:** Uses JSON for structured data serialization and deserialization between the client and server.
*   **Database Integration:** Connects to a PostgreSQL database to persist and manage all application data.

## Technologies Used

*   **Language:** Kotlin
*   **Database:** PostgreSQL
*   **Networking:** TCP/IP Sockets
*   **Data Format:** JSON
*   **Build Tool:** Gradle
*   **Version Control:** Git

## How To Run

**Prerequisites:**
*   Java 11+ | Kotlin 1.6+
*   PostgreSQL 12+
*   Gradle

**Server Setup:**
Configure the database connection details in `/src/main/kotlin/db/DatabaseManager.kt`.
```kotlin
private const val DB_HOST = "your_host"
private const val DB_NAME = "your_db_name"
private const val DB_USER = "your_user_name"
private const val DB_PASSWORD = "your_password"
private const val DB_PORT = 5432
