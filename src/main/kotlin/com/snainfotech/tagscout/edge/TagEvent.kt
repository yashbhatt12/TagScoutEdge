package com.snainfotech.tagscout.edge

/**
 * A single raw read as it comes OFF the reader.
 *
 * The reader produces hundreds of these per second per tag in the antenna field.
 * This is the shape going INTO the deduplicator.
 */
data class RawTagRead(
    val epc: String,           // normalised to uppercase hex
    val antennaPort: Int,      // 1-based antenna port number
    val rssi: Int,             // signal strength in dBm (negative; -40 strong, -80 weak)
    val readAtMillis: Long     // epoch millis when the reader saw it
)

/**
 * A deduped tag event — the shape going OUT of the deduplicator and
 * eventually INTO Firestore as a tag_events document.
 *
 * One TagEvent represents one meaningful "the tag appeared at this antenna"
 * occurrence. If the same tag lingers in the field for 5 seconds producing
 * 500 raw reads, that's still just ONE TagEvent (with rawReadCount=500,
 * firstSeenAt=t0, lastSeenAt=t0+5000).
 *
 * The composite dedup key is (epc, antennaPort) — same tag crossing a
 * different antenna is a distinct event because it means the tag moved.
 */
data class TagEvent(
    val epc: String,
    val antennaPort: Int,
    val rssi: Int,             // peak RSSI across the merged raw reads
    val firstSeenAt: Long,     // epoch millis of the first raw read in the window
    val lastSeenAt: Long,      // epoch millis of the most recent raw read in the window
    val rawReadCount: Int      // how many raw reads collapsed into this event
) {
    /** How long the tag lingered in the field, in milliseconds. */
    val dwellMillis: Long get() = lastSeenAt - firstSeenAt

    /** Composite dedup key — same tag on same antenna is the same event. */
    val dedupKey: String get() = "$epc@$antennaPort"
}