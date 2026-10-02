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

    TagScoutEdge starting â€” version 0.1.0
    JVM version: 21.0.x
    OS: Windows ...
    If you see this, the toolchain is working. Exiting.
