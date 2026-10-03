package com.snainfotech.tagscout.edge

import java.util.concurrent.ConcurrentHashMap

/**
 * Time-windowed dedup for raw tag reads.
 *
 * Collapses the firehose from the reader (100-500 reads/sec/tag) into
 * meaningful TagEvent occurrences. Composite key is (epc, antennaPort) —
 * same tag on different antennas is distinct because it implies the tag
 * moved between the two antenna fields.
 *
 * Lifecycle of a window:
 *   1. First raw read for a key arrives → window opens, nothing emitted
 *   2. More reads within windowMillis → window extended, nothing emitted
 *   3a. A later read for the same key arrives OUTSIDE windowMillis → old
 *       window closes (emitted as TagEvent), new window opens for the new read
 *   3b. No new read arrives; caller invokes flush() past the idle threshold
 *       → window closes, emitted as TagEvent
 *   4. At shutdown, flushAll() closes any still-open windows
 *
 * Thread safety: all mutating methods are @Synchronized. Reader IO threads
 * call offer(); a scheduler thread calls flush() every ~1 second.
 *
 * @param windowMillis how long after the last raw read before the window closes.
 *                     Default 2000ms — standard for retail portals.
 */
class Deduplicator(private val windowMillis: Long = 2_000L) {

    /** In-progress window state. Mutable because reads extend it in place. */
    private data class Window(
        val epc: String,
        val antennaPort: Int,
        var peakRssi: Int,
        val firstSeenAt: Long,
        var lastSeenAt: Long,
        var rawReadCount: Int
    ) {
        fun toEvent() = TagEvent(
            epc = epc,
            antennaPort = antennaPort,
            rssi = peakRssi,
            firstSeenAt = firstSeenAt,
            lastSeenAt = lastSeenAt,
            rawReadCount = rawReadCount
        )
    }

    private val windows = ConcurrentHashMap<String, Window>()

    /**
     * Offer a raw tag read for processing.
     *
     * @return a TagEvent if THIS read caused an old window to close
     *         (same key but outside the dedup window). Returns null if the
     *         read extended an existing window or started a brand-new one.
     *
     * Note: windows that time out without a new read for the same key are
     * NOT emitted by offer() — call flush() periodically to catch those.
     */
    @Synchronized
    fun offer(raw: RawTagRead): TagEvent? {
        val key = "${raw.epc}@${raw.antennaPort}"
        val existing = windows[key]

        if (existing == null) {
            // No prior window — open new, emit nothing.
            windows[key] = newWindowFrom(raw)
            return null
        }

        val gap = raw.readAtMillis - existing.lastSeenAt
        if (gap < windowMillis) {
            // Within window — extend, update stats, emit nothing.
            existing.lastSeenAt = raw.readAtMillis
            existing.rawReadCount++
            if (raw.rssi > existing.peakRssi) {
                existing.peakRssi = raw.rssi
            }
            return null
        }

        // Outside window — close old (emit), open new.
        val closed = existing.toEvent()
        windows[key] = newWindowFrom(raw)
        return closed
    }

    /**
     * Flush all windows idle for >= windowMillis as of nowMillis.
     * Call periodically (every ~1 second) to emit events for tags that
     * crossed and then disappeared.
     *
     * @return the closed events, in no particular order.
     */
    @Synchronized
    fun flush(nowMillis: Long = System.currentTimeMillis()): List<TagEvent> {
        if (windows.isEmpty()) return emptyList()

        val closed = mutableListOf<TagEvent>()
        val toRemove = mutableListOf<String>()

        for ((key, window) in windows) {
            if (nowMillis - window.lastSeenAt >= windowMillis) {
                closed += window.toEvent()
                toRemove += key
            }
        }

        for (key in toRemove) {
            windows.remove(key)
        }
        return closed
    }

    /**
     * Flush ALL windows regardless of age. Call at shutdown so no in-progress
     * read is lost.
     */
    @Synchronized
    fun flushAll(): List<TagEvent> {
        if (windows.isEmpty()) return emptyList()
        val closed = windows.values.map { it.toEvent() }
        windows.clear()
        return closed
    }

    /** Count of in-progress windows — useful for monitoring and tests. */
    val activeWindowCount: Int
        get() = windows.size

    private fun newWindowFrom(raw: RawTagRead) = Window(
        epc = raw.epc,
        antennaPort = raw.antennaPort,
        peakRssi = raw.rssi,
        firstSeenAt = raw.readAtMillis,
        lastSeenAt = raw.readAtMillis,
        rawReadCount = 1
    )
}