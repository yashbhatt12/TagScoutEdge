package com.snainfotech.tagscout.edge

import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.log4j.BasicConfigurator
import org.apache.log4j.Level
import org.apache.log4j.Logger

private val log = KotlinLogging.logger {}

// ── Dedup parameters ─────────────────────────────────────────────────
private const val DEDUP_WINDOW_MS = 2_000L

// ── Firestore parameters ─────────────────────────────────────────────
private const val SERVICE_ACCOUNT_PATH = "secrets/firebase-service-account.json"
private const val COMPANY_ID = "3DikZgMvwGQZkvCkzWo0ilyht2M2"
private const val READER_ID = "reader_main_exit"

/**
 * File 5c — dedup pipeline writes to Firestore.
 *
 * Same synthetic read stream as File 4c, but each closed TagEvent is written
 * to /companies/{COMPANY_ID}/tag_events/{auto-id}. After the run, check the
 * Firestore console — you should see 5 new documents.
 */
fun main(args: Array<String>) {
    BasicConfigurator.configure()
    Logger.getRootLogger().level = Level.WARN
    // Silence gRPC/Netty internals — they log every HTTP/2 frame at DEBUG.
    (org.slf4j.LoggerFactory.getLogger("io.grpc.netty.shaded.io.netty") as ch.qos.logback.classic.Logger).level =
        ch.qos.logback.classic.Level.WARN
    (org.slf4j.LoggerFactory.getLogger("io.grpc") as ch.qos.logback.classic.Logger).level =
        ch.qos.logback.classic.Level.WARN

    log.info { "TagScoutEdge starting - version 0.5.0 (Firestore write demo)" }
    log.info { "Dedup window: ${DEDUP_WINDOW_MS}ms" }
    log.info { "Target: /companies/$COMPANY_ID/tag_events/" }
    log.info { "" }

    // Initialise Firestore once up front — fails fast if key is missing.
    val writer = try {
        FirestoreWriter.getInstance(SERVICE_ACCOUNT_PATH, COMPANY_ID, READER_ID)
    } catch (e: Exception) {
        log.error(e) { "Failed to initialise Firestore writer" }
        return
    }

    val dedup = Deduplicator(DEDUP_WINDOW_MS)
    val closedEvents = mutableListOf<TagEvent>()

    val rawReads = buildSyntheticReads()
    log.info { "═══ Feeding ${rawReads.size} raw reads through dedup ═══" }

    for (raw in rawReads) {
        val closed = dedup.offer(raw)
        if (closed != null) {
            closedEvents += closed
            logEvent(closed, closedBy = "new-window")
        }
    }

    val finalNow = rawReads.last().readAtMillis + DEDUP_WINDOW_MS + 1
    val flushed = dedup.flush(finalNow)
    for (event in flushed) {
        closedEvents += event
        logEvent(event, closedBy = "flush")
    }

    // ── Write every closed event to Firestore ─────────────────────────
    log.info { "" }
    log.info { "═══ Writing ${closedEvents.size} events to Firestore ═══" }
    var writesOk = 0
    var writesFailed = 0
    for (event in closedEvents) {
        try {
            val docId = writer.write(event)
            log.info { "  ✓ Wrote ${event.epc.take(12)}… ant=${event.antennaPort} → $docId" }
            writesOk++
        } catch (e: Exception) {
            log.error(e) { "  ✗ Failed to write ${event.epc.take(12)}… ant=${event.antennaPort}" }
            writesFailed++
        }
    }

    log.info { "" }
    log.info { "═══ Summary ═══" }
    log.info { "  Raw reads in:        ${rawReads.size}" }
    log.info { "  TagEvents dedup out: ${closedEvents.size}" }
    log.info { "  Firestore writes OK: $writesOk" }
    log.info { "  Firestore failed:    $writesFailed" }
    log.info { "═══════════════" }
    log.info { "Done. Check Firebase Console → Firestore → companies/$COMPANY_ID/tag_events" }
}

/** Same synthetic stream as File 4c. Four scenarios producing 5 distinct TagEvents. */
private fun buildSyntheticReads(): List<RawTagRead> {
    val reads = mutableListOf<RawTagRead>()
    val t0 = 1_000_000L

    val epcA = "3005FB63AC1F3681EC880468"
    for (i in 0 until 150) {
        reads += RawTagRead(epcA, 1, -62 + (i / 10), t0 + (i * 10L))
    }

    val epcB = "E200470A24C0601215E0A7B2"
    val tB = t0 + 1500 + 500
    for (i in 0 until 80) {
        reads += RawTagRead(epcB, 1, -58 + (i / 20), tB + (i * 10L))
    }

    val tC = tB + 800 + 1000
    for (i in 0 until 60) {
        reads += RawTagRead(epcA, 2, -55, tC + (i * 15L))
    }

    val tD = tC + 900 + 3000
    val epcD1 = "A0B1C2D3E4F500000011AABB"
    val epcD2 = "A0B1C2D3E4F500000022CCDD"
    for (i in 0 until 50) {
        reads += RawTagRead(epcD1, 1, -50, tD + (i * 12L))
        reads += RawTagRead(epcD2, 3, -48, tD + (i * 12L))
    }

    return reads
}

private fun logEvent(e: TagEvent, closedBy: String) {
    log.info {
        "  → EPC=${e.epc.take(12)}… ant=${e.antennaPort} rssi=${e.rssi}dBm " +
                "dwell=${e.dwellMillis}ms reads=${e.rawReadCount} [$closedBy]"
    }
}