plugins {
    kotlin("jvm") version "2.0.21"
    application
}

group = "com.snainfotech.tagscout"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("com.snainfotech.tagscout.edge.MainKt")
}

dependencies {
    // Kotlin coroutines â€” for Flow-based reader streams and background workers.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Logging â€” structured logs to file and console.
    implementation("ch.qos.logback:logback-classic:1.5.12")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")

    // Dependencies added in later files:
    // LTKJava         â€” File 2 (Bluebird FR901 LLRP connection)
    // Firebase Admin  â€” File 3 (Firestore writes)
    // SQLite JDBC     â€” File 5 (local sold-EPC cache)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
