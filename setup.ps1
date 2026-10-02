# TagScoutEdge setup script
# Creates all skeleton files in one shot.
# Run this inside your TagScoutEdge project folder.

Write-Host "Creating TagScoutEdge project files..." -ForegroundColor Cyan

# ── Create folder structure for Kotlin source ─────────────────────────
New-Item -ItemType Directory -Force -Path "src\main\kotlin\com\snainfotech\tagscout\edge" | Out-Null
Write-Host "  Created src/main/kotlin/com/snainfotech/tagscout/edge/" -ForegroundColor Gray

# ── settings.gradle.kts ───────────────────────────────────────────────
@'
rootProject.name = "TagScoutEdge"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}
'@ | Set-Content -Path "settings.gradle.kts" -Encoding UTF8
Write-Host "  Created settings.gradle.kts" -ForegroundColor Gray

# ── build.gradle.kts ──────────────────────────────────────────────────
@'
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
    // Kotlin coroutines — for Flow-based reader streams and background workers.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Logging — structured logs to file and console.
    implementation("ch.qos.logback:logback-classic:1.5.12")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")

    // Dependencies added in later files:
    // LTKJava         — File 2 (Bluebird FR901 LLRP connection)
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
'@ | Set-Content -Path "build.gradle.kts" -Encoding UTF8
Write-Host "  Created build.gradle.kts" -ForegroundColor Gray

# ── gradle.properties ─────────────────────────────────────────────────
@'
kotlin.code.style=official
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
'@ | Set-Content -Path "gradle.properties" -Encoding UTF8
Write-Host "  Created gradle.properties" -ForegroundColor Gray

# ── .gitignore ────────────────────────────────────────────────────────
@'
# Gradle
.gradle/
build/
!gradle/wrapper/gradle-wrapper.jar

# IntelliJ
.idea/
*.iml
*.iws
*.ipr
out/

# OS
.DS_Store
Thumbs.db

# Logs
*.log
logs/

# Local service account keys — NEVER commit these
*serviceAccount*.json
*firebase-adminsdk*.json
secrets/
*.jks
*.keystore

# Local config
local.properties
config.local.properties

# SQLite cache files
*.db
*.db-journal
*.db-shm
*.db-wal
'@ | Set-Content -Path ".gitignore" -Encoding UTF8
Write-Host "  Created .gitignore" -ForegroundColor Gray

# ── README.md ─────────────────────────────────────────────────────────
@'
# TagScoutEdge

Headless Windows edge service that bridges Bluebird FR901 fixed RFID readers to Firebase for the TagScout platform.

## What it does

Runs as a Windows Service on a small PC at each customer site. Connects to one or more FR901 readers over the local network via LLRP, dedupes the firehose of raw tag reads into meaningful events, maintains a local cache of sold-EPC decisions for alarm resilience, and streams events to Firestore.

## Stack

- Kotlin 2.0.21 on JDK 21
- LTKJava for LLRP reader connection (added File 2)
- Firebase Admin SDK for Java for Firestore writes (added File 3)
- SQLite via JDBC for local offline buffer (added File 5)
- Logback + kotlin-logging for structured logs

## Status

Early development. Current file set is a skeleton proving the toolchain compiles and runs.

## Build and run

From the project root:

    .\gradlew.bat run

Expected output (stub phase):

    TagScoutEdge starting — version 0.1.0
    JVM version: 21.0.x
    OS: Windows ...
    If you see this, the toolchain is working. Exiting.
'@ | Set-Content -Path "README.md" -Encoding UTF8
Write-Host "  Created README.md" -ForegroundColor Gray

# ── Main.kt ───────────────────────────────────────────────────────────
@'
package com.snainfotech.tagscout.edge

import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * TagScoutEdge entry point.
 *
 * At this stage: just proves the toolchain works end-to-end.
 * Future files will add:
 *   - LTKJava connection to the FR901 reader
 *   - Firebase Admin SDK writes to tag_events
 *   - SQLite local sold-EPC cache
 *   - Realtime Firestore listener for cache updates
 *   - Windows Service wrapper for headless deployment
 */
fun main(args: Array<String>) {
    log.info { "TagScoutEdge starting — version 0.1.0" }
    log.info { "JVM version: ${System.getProperty("java.version")}" }
    log.info { "OS: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}" }
    log.info { "Working directory: ${System.getProperty("user.dir")}" }
    log.info { "If you see this, the toolchain is working. Exiting." }
}
'@ | Set-Content -Path "src\main\kotlin\com\snainfotech\tagscout\edge\Main.kt" -Encoding UTF8
Write-Host "  Created src/main/kotlin/com/snainfotech/tagscout/edge/Main.kt" -ForegroundColor Gray

Write-Host ""
Write-Host "Done! All 6 files created." -ForegroundColor Green
Write-Host ""
Write-Host "Next steps:" -ForegroundColor Yellow
Write-Host "  1. Open the TagScoutEdge folder in IntelliJ (if not already open)"
Write-Host "  2. Click 'Load Gradle Changes' when the yellow banner appears"
Write-Host "  3. Open Main.kt and click the green arrow next to 'fun main'"
