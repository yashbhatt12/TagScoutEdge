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

    // WebSocket client for talking to FR901's native web API (preferred over LLRP)
    implementation("org.java-websocket:Java-WebSocket:1.5.7")

    // LLRP toolkit for talking to the FR901 reader over the LAN
    implementation("org.llrp:ltkjava:1.0.0.7") {
        exclude(group = "javax.jms", module = "jms")
        exclude(group = "com.sun.jdmk", module = "jmxtools")
        exclude(group = "com.sun.jmx", module = "jmxri")
    }

    // Firebase Admin SDK for Firestore writes
    implementation("com.google.firebase:firebase-admin:9.4.1")

    // Dependencies added in later files:
    // SQLite JDBC     — File 6 (local sold-EPC cache)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
