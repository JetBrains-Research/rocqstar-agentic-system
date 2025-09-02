plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("ai.koog:koog-agents:0.4.1")
    implementation("org.slf4j:slf4j-simple:2.0.17")
    implementation("io.github.cdimascio:dotenv-kotlin:6.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}