package com.snainfotech.tagscout.edge

import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe in-memory cache of EPCs that have been marked sold in Firestore.
 *
 * This is the authority the edge service consults on every exit-portal tag read
 * to decide "alarm or no alarm":
 *   - EPC is in cache → sold, let it leave quietly
 *   - EPC NOT in cache → either unsold or we have stale data
 *     (the alarm policy — fire-anyway when cloud is unreachable — is enforced
 *      by whoever calls isSold(), not by this class)
 *
 * The cache is kept fresh by SoldEpcRefresher (File 6b):
 *   - every refreshIntervalMs: pull all sold items from Firestore and `replace()`
 *   - realtime listener on inventoryUnits where status == "sold": `add()` as sales happen
 *
 * Design: AtomicReference<Set<String>> rather than a synchronized mutable set.
 * isSold() runs on the critical path of every portal read — it must be
 * lock-free. replace() atomically swaps the whole snapshot; the reads either
 * see the old set or the new set, never a half-updated one.
 *
 * EPCs are stored UPPERCASE for consistent comparison with RawTagRead.epc
 * (which the reader produces in whatever case LTKJava chose; our Deduplicator
 * already normalises). This class also normalises defensively on every input.
 */
class SoldEpcCache {

    /** Immutable snapshot held behind an atomic ref — read without locking. */
    private val snapshotRef = AtomicReference<Set<String>>(emptySet())

    /** Epoch millis when the snapshot was last fully replaced. 0 = never refreshed. */
    @Volatile
    private var lastRefreshedAtMillis: Long = 0L

    /**
     * The hot path — called on every tag event crossing the portal.
     * Zero locking, pure in-memory set lookup.
     *
     * @return true if the EPC is in the current sold snapshot
     */
    fun isSold(epc: String): Boolean {
        return snapshotRef.get().contains(epc.uppercase())
    }

    /**
     * Atomically replace the entire snapshot. Called by the refresher after
     * pulling a fresh "all sold items" result from Firestore.
     *
     * @param soldEpcs all EPCs currently marked sold in Firestore
     */
    fun replace(soldEpcs: Collection<String>) {
        val normalised = soldEpcs.mapTo(HashSet(soldEpcs.size)) { it.uppercase() }
        snapshotRef.set(normalised)
        lastRefreshedAtMillis = System.currentTimeMillis()
    }

    /**
     * Add a single EPC to the current snapshot. Called by the realtime listener
     * when a new sale propagates from Firestore between full refreshes.
     *
     * Uses compareAndSet so it coexists correctly with a concurrent replace().
     * If a replace() lands between our read and our CAS, we retry on the new
     * snapshot — the add never gets lost.
     */
    fun add(epc: String) {
        val upper = epc.uppercase()
        while (true) {
            val current = snapshotRef.get()
            if (upper in current) return  // already present, nothing to do
            val updated = current + upper
            if (snapshotRef.compareAndSet(current, updated)) return
            // CAS failed — another thread swapped the snapshot. Retry.
        }
    }

    /**
     * Remove a single EPC from the snapshot. Reserved for a future "unsell"
     * flow — not used in v1 since the Android app has no unsell action.
     */
    fun remove(epc: String) {
        val upper = epc.uppercase()
        while (true) {
            val current = snapshotRef.get()
            if (upper !in current) return
            val updated = current - upper
            if (snapshotRef.compareAndSet(current, updated)) return
        }
    }

    /** Count of sold EPCs in the current snapshot. */
    val size: Int
        get() = snapshotRef.get().size

    /**
     * Milliseconds since the last full replace(). Useful for logging and for
     * the alarm decision — if the cache is very stale AND cloud is unreachable,
     * confidence in isSold() is low.
     *
     * Returns Long.MAX_VALUE if replace() has never been called.
     */
    fun ageMillis(): Long {
        if (lastRefreshedAtMillis == 0L) return Long.MAX_VALUE
        return System.currentTimeMillis() - lastRefreshedAtMillis
    }

    /** True once replace() has been called at least once. */
    val hasEverRefreshed: Boolean
        get() = lastRefreshedAtMillis != 0L
}