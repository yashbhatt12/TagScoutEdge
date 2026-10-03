package com.snainfotech.tagscout.edge

import ch.qos.logback.classic.Level as LogbackLevel
import ch.qos.logback.classic.Logger as LogbackLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.log4j.BasicConfigurator
import org.apache.log4j.Level
import org.apache.log4j.Logger
import org.slf4j.LoggerFactory

private val log = KotlinLogging.logger {}

// ── Dedup parameters ─────────────────────────────────────────────────
private const val DEDUP_WINDOW_MS = 2_000L

// ── Firestore parameters ─────────────────────────────────────────────
private const val SERVICE_ACCOUNT_PATH = "secrets/firebase-service-account.json"
private const val COMPANY_ID = "3DikZgMvwGQZkvCkzWo0ilyht2M2"
private const val READER_ID = "reader_main_exit"

// ── Demo scenario EPCs ───────────────────────────────────────────────
// Change EPC_SOLD to an EPC you've actually marked sold in the Android app
// to see scenario 3 fire ALLOW. The others fire ALARM regardless of cache state.
private const val EPC_UNKNOWN = "AAAA0000000000000000UNKN"
private const val EPC_ENROLLED_UNSOLD = "BBBB0000000000000000UNSD"
private const val EPC_SOLD = "CCCC0000000000000000SOLD"
private const val EPC_SECOND_UNSOLD = "DDDD0000000000000000UNS2"

/**
 * File 6c — full edge pipeline with sold-cache alarm decisions.
 *
 * On each deduped TagEvent, we consult the live SoldEpcCache (populated from
 * Firestore via SoldEpcRefresher) and decide ALLOW or ALARM. All events are
 * still written to Firestore for audit; alarm decisions are the hot path.
 */
fun main(args: Array<String>) {
    silenceVerboseLogging()

    log.info { "TagScoutEdge starting - version 0.6.0 (sold-cache + alarm decisions)" }
    log.info { "" }

    // ── Initialise Firestore writer + sold-cache refresher ──────────
    val writer = try {
        FirestoreWriter.getInstance(SERVICE_ACCOUNT_PATH, COMPANY_ID, READER_ID)
    } catch (e: Exception) {
        log.error(e) { "Failed to initialise Firestore writer" }
        return
    }

    val cache = SoldEpcCache()
    val refresher = SoldEpcRefresher(cache, COMPANY_ID)

    // Clean shutdown on Ctrl+C (irrelevant for a 10-second demo but
    // habit-forming for the Windows Service wrapper later).
    Runtime.getRuntime().addShutdownHook(Thread {
        log.info { "Shutting down..." }
        refresher.stop()
    })

    refresher.start()  // blocks until initial cache populated

    log.info { "" }
    log.info { "═══ Cache status ═══" }
    log.info { "  Sold EPCs in cache: ${cache.size}" }
    log.info { "  Cache age:          ${cache.ageMillis()}ms" }
    log.info { "═══════════════════" }
    log.info { "" }

    // ── Build the demo read stream (4 scenarios) ────────────────────
    val reads = buildDemoReads()
    log.info { "═══ Feeding ${reads.size} raw reads (4 jewellery scenarios) ═══" }

    val dedup = Deduplicator(DEDUP_WINDOW_MS)
    val closedEvents = mutableListOf<TagEvent>()

    for (raw in reads) {
        dedup.offer(raw)?.let { closedEvents += it }
    }
    val finalNow = reads.last().readAtMillis + DEDUP_WINDOW_MS + 1
    closedEvents += dedup.flush(finalNow)

    // ── Alarm decision + Firestore write ────────────────────────────
    log.info { "" }
    log.info { "═══ Processing ${closedEvents.size} TagEvents ═══" }
    var alarms = 0
    var allowed = 0
    for (event in closedEvents) {
        val isSold = cache.isSold(event.epc)
        val decision = if (isSold) "ALLOW" else "ALARM"
        val icon = if (isSold) "✓" else "🚨"
        if (isSold) allowed++ else alarms++

        log.info {
            "  $icon $decision  EPC=${event.epc}  ant=${event.antennaPort}  " +
                    "rssi=${event.rssi}dBm  reads=${event.rawReadCount}"
        }

        // Write audit record to Firestore regardless of decision.
        try {
            writer.write(event)
        } catch (e: Exception) {
            log.error { "    (Firestore write failed: ${e.message})" }
        }
    }

    log.info { "" }
    log.info { "═══ Summary ═══" }
    log.info { "  Raw reads in:        ${reads.size}" }
    log.info { "  TagEvents dedup out: ${closedEvents.size}" }
    log.info { "  Alarms fired:        $alarms" }
    log.info { "  Sales allowed:       $allowed" }
    log.info { "  Sold cache size:     ${cache.size}" }
    log.info { "═══════════════" }

    refresher.stop()
    log.info { "Done." }
}

/**
 * Four realistic jewellery-store scenarios.
 *   1. Unknown tag crosses       → should ALARM (not in cache, not enrolled)
 *   2. Enrolled but unsold       → should ALARM (theft case)
 *   3. Enrolled and sold         → should ALLOW (legitimate sale leaving)
 *   4. Two tags simultaneously   → one ALLOW, one ALARM
 */
private fun buildDemoReads(): List<RawTagRead> {
    val reads = mutableListOf<RawTagRead>()
    val t0 = 1_000_000L

    // Scenario 1: unknown tag — 50 reads on antenna 1
    for (i in 0 until 50) {
        reads += RawTagRead(EPC_UNKNOWN, 1, -55, t0 + i * 10L)
    }

    // Scenario 2: enrolled but unsold — 60 reads on antenna 1, 3s later
    val t2 = t0 + 500 + 3_000
    for (i in 0 until 60) {
        reads += RawTagRead(EPC_ENROLLED_UNSOLD, 1, -50, t2 + i * 10L)
    }

    // Scenario 3: enrolled AND sold — 70 reads on antenna 1, 3s later
    val t3 = t2 + 600 + 3_000
    for (i in 0 until 70) {
        reads += RawTagRead(EPC_SOLD, 1, -48, t3 + i * 10L)
    }

    // Scenario 4: two tags simultaneously on antennas 1 and 2, 3s later
    val t4 = t3 + 700 + 3_000
    for (i in 0 until 40) {
        reads += RawTagRead(EPC_SOLD, 1, -52, t4 + i * 12L)         // sold, antenna 1 — ALLOW
        reads += RawTagRead(EPC_SECOND_UNSOLD, 2, -49, t4 + i * 12L) // unsold, antenna 2 — ALARM
    }

    return reads
}

/** Silences gRPC/Netty internal DEBUG spam. */
private fun silenceVerboseLogging() {
    BasicConfigurator.configure()
    Logger.getRootLogger().level = Level.WARN
    (LoggerFactory.getLogger("io.grpc.netty.shaded.io.netty") as LogbackLogger).level = LogbackLevel.WARN
    (LoggerFactory.getLogger("io.grpc") as LogbackLogger).level = LogbackLevel.WARN
    (LoggerFactory.getLogger("io.netty") as LogbackLogger).level = LogbackLevel.WARN
}