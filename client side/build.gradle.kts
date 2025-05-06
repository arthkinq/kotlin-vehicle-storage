plugins {
    kotlin("jvm") version "1.9.0"
    application
    kotlin("plugin.serialization") version "1.9.0"
    id("com.github.johnrengelman.shadow") version "7.1.2"
    id("org.jetbrains.dokka") version "2.0.0"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    implementation("org.junit.jupiter:junit-jupiter:5.9.2")
    implementation("io.mockk:mockk:1.13.5")
    implementation("org.apache.commons:commons-csv:1.10.0")
    implementation("ch.qos.logback:logback-classic:1.4.12")
    implementation("io.mockk:mockk:1.13.8")
    implementation(kotlin("test"))
    implementation("org.junit.jupiter:junit-jupiter-api:5.9.2")
    runtimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.2")
    implementation("org.assertj:assertj-core:3.24.2")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("org.example.MainKt")
}

tasks {
    shadowJar {
        archiveBaseName.set("object-kotlin-storage")
        archiveVersion.set("1.0")
        archiveClassifier.set("")
        manifest {
            attributes["Main-Class"] = application.mainClass.get()
        }
    }
}