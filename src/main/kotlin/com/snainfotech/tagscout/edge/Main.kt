package com.snainfotech.tagscout.edge

import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.log4j.BasicConfigurator
import org.apache.log4j.Level
import org.apache.log4j.Logger

private val log = KotlinLogging.logger {}

// ── Dedup parameters ─────────────────────────────────────────────────
private const val DEDUP_WINDOW_MS = 2_000L

/**
 * File 4c — dedup pipeline demo with synthetic reads.
 *
 * Generates realistic raw reads (one tag seen hundreds of times during a
 * ~1.5s "crossing", another distinct tag, two tags on different antennas,
 * etc.), feeds them through the Deduplicator, and prints the collapse ratio.
 *
 * When the reader comes back online, main() will instead subscribe to the
 * live LLRP stream and feed each RawTagRead through the SAME deduplicator —
 * dedup logic stays unchanged.
 */
fun main(args: Array<String>) {
    BasicConfigurator.configure()
    Logger.getRootLogger().level = Level.WARN

    log.info { "TagScoutEdge starting - version 0.4.0 (dedup demo)" }
    log.info { "Dedup window: ${DEDUP_WINDOW_MS}ms" }
    log.info { "" }

    val dedup = Deduplicator(DEDUP_WINDOW_MS)
    val closedEvents = mutableListOf<TagEvent>()

    // Build a synthetic read stream representing realistic portal scenarios.
    val rawReads = buildSyntheticReads()
    log.info { "═══ Feeding ${rawReads.size} raw reads through dedup ═══" }

    // Process every raw read in order. Any closed events are collected.
    for (raw in rawReads) {
        val closed = dedup.offer(raw)
        if (closed != null) {
            closedEvents += closed
            logEvent(closed, closedBy = "new-window")
        }
    }

    // Final flush: emit any windows still open past the final read time.
    val finalNow = rawReads.last().readAtMillis + DEDUP_WINDOW_MS + 1
    val flushed = dedup.flush(finalNow)
    for (event in flushed) {
        closedEvents += event
        logEvent(event, closedBy = "flush")
    }

    // Summary.
    log.info { "" }
    log.info { "═══ Dedup summary ═══" }
    log.info { "  Raw reads in:        ${rawReads.size}" }
    log.info { "  TagEvents out:       ${closedEvents.size}" }
    log.info { "  Collapse ratio:      ${rawReads.size / closedEvents.size.coerceAtLeast(1)}:1" }
    log.info { "  Unique tag/antenna:  ${closedEvents.map { it.dedupKey }.distinct().size}" }
    log.info { "═════════════════════" }
    log.info { "Done." }
}

/**
 * Builds a synthetic read stream representing four realistic scenarios:
 *   A) One tag crossing antenna 1 slowly — 150 reads over 1500ms
 *   B) A different tag on the same antenna, 500ms later — 80 reads over 800ms
 *   C) The SAME tag as A reappears on antenna 2 — new dedup key, 60 reads
 *   D) Two tags simultaneously on antennas 1 and 3, 3s later — fresh windows
 */
private fun buildSyntheticReads(): List<RawTagRead> {
    val reads = mutableListOf<RawTagRead>()
    val t0 = 1_000_000L  // arbitrary epoch base

    // A) EPC_A on antenna 1, 150 reads over 1500ms, RSSI varying -62 to -45
    val epcA = "3005FB63AC1F3681EC880468"
    for (i in 0 until 150) {
        reads += RawTagRead(
            epc = epcA,
            antennaPort = 1,
            rssi = -62 + (i / 10),  // signal strengthens as tag approaches
            readAtMillis = t0 + (i * 10L)  // one read per 10ms = 100 Hz
        )
    }

    // B) EPC_B on antenna 1, starts 500ms AFTER A ended (fresh window)
    val epcB = "E200470A24C0601215E0A7B2"
    val tB = t0 + 1500 + 500
    for (i in 0 until 80) {
        reads += RawTagRead(
            epc = epcB,
            antennaPort = 1,
            rssi = -58 + (i / 20),
            readAtMillis = tB + (i * 10L)
        )
    }

    // C) EPC_A reappears on ANTENNA 2 (different dedup key → distinct event)
    //    1 second after B ends
    val tC = tB + 800 + 1000
    for (i in 0 until 60) {
        reads += RawTagRead(
            epc = epcA,
            antennaPort = 2,
            rssi = -55,
            readAtMillis = tC + (i * 15L)
        )
    }

    // D) 3 seconds later, two tags simultaneously on antennas 1 and 3
    val tD = tC + 900 + 3000
    val epcD1 = "A0B1C2D3E4F500000011AABB"
    val epcD2 = "A0B1C2D3E4F500000022CCDD"
    for (i in 0 until 50) {
        reads += RawTagRead(
            epc = epcD1,
            antennaPort = 1,
            rssi = -50,
            readAtMillis = tD + (i * 12L)
        )
        reads += RawTagRead(
            epc = epcD2,
            antennaPort = 3,
            rssi = -48,
            readAtMillis = tD + (i * 12L)
        )
    }

    return reads
}

private fun logEvent(e: TagEvent, closedBy: String) {
    log.info {
        "  → EPC=${e.epc.take(12)}… ant=${e.antennaPort} rssi=${e.rssi}dBm " +
                "dwell=${e.dwellMillis}ms reads=${e.rawReadCount} [$closedBy]"
    }
}