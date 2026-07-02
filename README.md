# Vehicle Manager (Client-Server Application)

![Kotlin](https://img.shields.io/badge/kotlin-%237F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white)
![JavaFX](https://img.shields.io/badge/javafx-%23FF0000.svg?style=for-the-badge&logo=java&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/postgresql-%23316192.svg?style=for-the-badge&logo=postgresql&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-02303A.svg?style=for-the-badge&logo=Gradle&logoColor=white)

A multi-user, TCP-based application for managing a collection of vehicle objects stored in a PostgreSQL database. The project features a robust multi-threaded server and a rich **JavaFX Graphical User Interface (GUI)** with interactive map visualization.

Developed as part of the university curriculum at ITMO University to demonstrate core backend development, concurrent programming, and GUI design principles.

## Key Features

*   **Rich Graphical Interface (GUI):** Built with JavaFX, featuring data tables, interactive command menus, and real-time alerts.
*   **Interactive Map Visualization:** Real-time 2D rendering of all vehicles on a coordinate plane, with dynamic updates and animations when vehicles are added or modified.
*   **Multi-User Environment:** Supports multiple clients connecting to a central server simultaneously via TCP/IP sockets.
*   **User Authentication:** Secure user registration, login system, and access control (password hashing implemented).
*   **Comprehensive Command Set:** Supports 15+ commands for full CRUD operations, complex sorting, and filtering of the vehicle collection.
*   **Reliable Data Transfer:** Uses JSON/Serialization for structured data exchange between the client and server.
*   **Database Integration:** Seamlessly connects to a PostgreSQL database for persistent storage of users and vehicles.

## Technologies Used

*   **Language:** Kotlin
*   **GUI Framework:** JavaFX
*   **Database:** PostgreSQL
*   **Networking:** TCP/IP Sockets (NIO)
*   **Build Tool:** Gradle

## How To Run

### Prerequisites
*   Java 11+ / Kotlin 1.6+ (For client execution)
*   Docker & Docker Compose (For server and database)
*   Gradle

### 1. Server & Database Setup (via Docker)

The easiest way to run the server and the PostgreSQL database is using Docker Compose.

1. Ensure Docker is installed and running on your machine.
2. In the root directory of the project, run:
   ```bash
   docker-compose up -d --build
   ```
   This command will automatically:
   * Pull the PostgreSQL image and configure the database (`proga`).
   * Build the Kotlin server from source.
   * Start both containers and link them together.
   
3. To stop the server and database, run:
   ```bash
   docker-compose down
   ```

### 2. Client Setup

Since the client features a rich graphical interface (JavaFX), it is designed to be run locally on your host machine.

1. Navigate to the `client side` directory.
2. Ensure the server IP and PORT are correctly set in the client connection settings (default connects to `localhost:8888` which is exposed by Docker).
3. Build and run the JavaFX client via Gradle:
   ```bash
   ./gradlew run
   ```

## License
This project is for educational purposes.
