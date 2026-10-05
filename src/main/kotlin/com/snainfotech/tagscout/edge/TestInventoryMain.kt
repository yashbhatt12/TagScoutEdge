package com.snainfotech.tagscout.edge

import ch.qos.logback.classic.Level as LogbackLevel
import ch.qos.logback.classic.Logger as LogbackLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

private val testLog = KotlinLogging.logger("TestInventory")

// ── Reader connection parameters ─────────────────────────────────────
private const val TEST_READER_URL = "wss://192.168.1.111:7681"
private const val TEST_READER_USER = "admin"
private const val TEST_READER_PASS = "bluebird"

// ── Inventory parameters ─────────────────────────────────────────────
private const val TEST_INVENTORY_DURATION_MS = 30_000L
private const val TEST_POLL_INTERVAL_MS = 200L

private val testTagReadCount = AtomicLong(0)
private val testUniqueEpcs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

/**
 * TestInventoryMain — hardware smoke test via WebSocket API.
 *
 * Confirms end-to-end: Kotlin → WSS → reader → tag reads → our code.
 * Pass criterion: wave a tag during the 30s window, see [N] EPC=... lines.
 *
 * Run with the TSC web console CLOSED (shared reader session).
 */
fun main() {
    silenceVerboseLogging()

    testLog.info { "═══════════════════════════════════════════════" }
    testLog.info { "  TagScoutEdge WEBSOCKET SMOKE TEST" }
    testLog.info { "  Reader: $TEST_READER_URL" }
    testLog.info { "  Duration: ${TEST_INVENTORY_DURATION_MS / 1000}s" }
    testLog.info { "  Poll interval: ${TEST_POLL_INTERVAL_MS}ms" }
    testLog.info { "═══════════════════════════════════════════════" }
    testLog.info { "" }
    testLog.info { "Checklist:" }
    testLog.info { "  1. Reader powered on and reachable (ping 192.168.1.111)" }
    testLog.info { "  2. TSC web console browser tab CLOSED" }
    testLog.info { "  3. Antenna connected to port 1" }
    testLog.info { "  4. A tag ready to wave in front of the antenna" }
    testLog.info { "" }

    val client = WebSocketReaderClient(
        readerUrl = TEST_READER_URL,
        username = TEST_READER_USER,
        password = TEST_READER_PASS,
        pollIntervalMillis = TEST_POLL_INTERVAL_MS,
        onRead = ::onTagRead
    )

    try {
        client.connectAndLogin()
        client.startInventory()

        testLog.info { "" }
        testLog.info { "═══ INVENTORY RUNNING — WAVE A TAG NOW ═══" }

        val chunks = (TEST_INVENTORY_DURATION_MS / 5_000).toInt()
        for (i in 1..chunks) {
            Thread.sleep(5_000)
            testLog.info {
                "  ... ${(chunks - i) * 5}s remaining, reads so far: ${testTagReadCount.get()}, " +
                        "unique EPCs: ${testUniqueEpcs.size}"
            }
        }

        testLog.info { "" }
        testLog.info { "═══ Stopping inventory ═══" }
        client.stopInventory()
    } catch (e: Exception) {
        testLog.error(e) { "Error during inventory run" }
    } finally {
        try {
            client.close()
            testLog.info { "WebSocket closed cleanly." }
        } catch (e: Exception) {
            testLog.warn { "Error during close (safe to ignore): ${e.message}" }
        }
    }

    testLog.info { "" }
    testLog.info { "═══ RESULT ═══" }
    val total = testTagReadCount.get()
    val unique = testUniqueEpcs.size
    if (total > 0) {
        testLog.info { "  ✓ SUCCESS: $total raw reads from $unique unique EPCs." }
        testLog.info { "  WebSocket path proven. Ready for File 7d (wire through dedup + Firestore)." }
        testLog.info { "" }
        testLog.info { "  Unique EPCs seen:" }
        testUniqueEpcs.forEach { epc -> testLog.info { "    $epc" } }
    } else {
        testLog.info { "  ✗ ZERO TAG READS. Troubleshoot:" }
        testLog.info { "    - Was the TSC web console tab OPEN? Only one WS client allowed." }
        testLog.info { "    - Was a tag physically waved during the 30s window?" }
        testLog.info { "    - Try closer distance (20cm) and different orientations." }
        testLog.info { "    - Login failed silently? Re-check credentials." }
    }
    testLog.info { "═════════════" }
}

/**
 * Callback invoked once per tag entry in each QUERY_READTAGS response.
 * Prints the first few reads per EPC in detail; subsequent reads just increment the counter.
 */
private fun onTagRead(raw: RawTagRead) {
    val n = testTagReadCount.incrementAndGet()
    val isNewEpc = testUniqueEpcs.add(raw.epc)
    if (isNewEpc) {
        testLog.info { "[$n] NEW EPC=${raw.epc}  antenna=${raw.antennaPort}  RSSI=${raw.rssi}dBm" }
    } else if (n <= 20 || n % 50 == 0L) {
        // First 20 reads chatty, then every 50th to avoid log spam
        testLog.info { "[$n] EPC=${raw.epc}  antenna=${raw.antennaPort}  RSSI=${raw.rssi}dBm" }
    }
}

/** Suppress WebSocket library noise. */
private fun silenceVerboseLogging() {
    (LoggerFactory.getLogger("org.java_websocket") as LogbackLogger).level = LogbackLevel.WARN
    (LoggerFactory.getLogger("org.java-websocket") as LogbackLogger).level = LogbackLevel.WARN
}