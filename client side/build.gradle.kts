plugins {
    kotlin("jvm") version "1.9.0"
    application
    kotlin("plugin.serialization") version "1.9.0"
    id("com.github.johnrengelman.shadow") version "7.1.2"
    id("org.jetbrains.dokka") version "2.0.0"
    id("org.openjfx.javafxplugin") version "0.0.13"

}

group = ""
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

javafx {
    version = "21.0.5"
    modules = listOf("javafx.controls", "javafx.fxml", "javafx.graphics")
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
    implementation ("no.tornado:tornadofx:1.7.20")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("MainKt")
}

tasks {
    shadowJar {
        archiveBaseName.set("Transport manager client")
        archiveVersion.set("1.0")
        archiveClassifier.set("")
        manifest {
            attributes["Main-Class"] = application.mainClass.get()
        }
    }
}