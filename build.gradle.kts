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

    // LLRP toolkit for talking to the FR901 reader over the LAN
    implementation("org.llrp:ltkjava:1.0.0.7") {
        // log4j 1.x pulls in old Sun artifacts that Oracle never released to Maven Central.
        // None of these are needed for LLRP — exclude so dependency resolution succeeds.
        exclude(group = "javax.jms", module = "jms")
        exclude(group = "com.sun.jdmk", module = "jmxtools")
        exclude(group = "com.sun.jmx", module = "jmxri")
    }

    // Dependencies added in later files:
    // Firebase Admin  — File 3 (Firestore writes)
    // SQLite JDBC     — File 5 (local sold-EPC cache)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
