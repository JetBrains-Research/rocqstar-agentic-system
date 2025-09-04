plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal()
    maven {
        url = uri("https://packages.jetbrains.team/maven/p/grazi/grazie-platform-public")
    }
}

dependencies {
    testImplementation(kotlin("test"))

    implementation(libs.koog)
    implementation(libs.grazie.koog.executor)
    implementation(libs.grazie.ktor)
    implementation(libs.grazie.gateway)
    implementation(libs.ktor.okhttp)

    implementation(libs.jackson.kotlin)
    implementation(libs.jackson.yaml)
    implementation(libs.kotlin.serialization)

    implementation(libs.slf4j)
    implementation(libs.dotenv)
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}