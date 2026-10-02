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
    log.info { "TagScoutEdge starting â€” version 0.1.0" }
    log.info { "JVM version: ${System.getProperty("java.version")}" }
    log.info { "OS: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}" }
    log.info { "Working directory: ${System.getProperty("user.dir")}" }
    log.info { "If you see this, the toolchain is working. Exiting." }
}
