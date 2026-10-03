package com.snainfotech.tagscout.edge

import com.google.cloud.firestore.EventListener
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreException
import com.google.cloud.firestore.ListenerRegistration
import com.google.cloud.firestore.Query
import com.google.cloud.firestore.QueryDocumentSnapshot
import com.google.cloud.firestore.QuerySnapshot
import com.google.firebase.cloud.FirestoreClient
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * Keeps SoldEpcCache populated with current Firestore data via two mechanisms:
 *
 *   1. Periodic full refresh every refreshIntervalMs — belt. Queries every
 *      inventory_unit where status == "sold" and atomically replaces the cache.
 *      Catches drift and handles transient listener failures.
 *
 *   2. Realtime listener on the same query — braces. Firestore pushes doc
 *      changes within ~1-2 seconds; new sales add to the cache near-instantly.
 *
 * Both run during normal operation. The polling is the safety net; the listener
 * is the fast path.
 *
 * SCOPE NOTE: queries "status == sold" with no time bound, so returns every
 * item ever sold by this customer. Fine for memory at pilot scale (10K items
 * ≈ 14 KB), but for production should probably add soldAt > (now - 90 days).
 * Deferred — not a demo concern.
 *
 * Not thread-safe to start()/stop() concurrently, but all other methods are
 * fine from any thread (SoldEpcCache handles that).
 */
class SoldEpcRefresher(
    private val cache: SoldEpcCache,
    private val companyId: String,
    private val refreshIntervalMillis: Long = 5 * 60 * 1_000L  // 5 minutes
) {

    private val db: Firestore = FirestoreClient.getFirestore()

    private var scheduler: ScheduledExecutorService? = null
    private var listenerRegistration: ListenerRegistration? = null

    /**
     * Starts both the polling scheduler and the realtime listener.
     *
     * The initial refresh runs SYNCHRONOUSLY — this method blocks until the
     * cache is warm. That way main() can trust the cache from the first portal
     * read without a race against the first poll.
     *
     * Subsequent refreshes run on a background scheduler thread.
     */
    fun start() {
        log.info { "Starting sold-EPC refresher (poll every ${refreshIntervalMillis / 1000}s + realtime listener)" }

        // Initial refresh — blocking so cache is warm before we return.
        try {
            refreshNow()
            log.info { "Initial sold cache populated: ${cache.size} EPCs" }
        } catch (e: Exception) {
            log.error(e) { "Initial sold cache refresh failed - cache starts empty" }
            // Don't throw — we can still function (every unknown EPC fires alarm,
            // which is the safe-default-when-cloud-unreachable behaviour).
        }

        // Scheduled polling for subsequent refreshes.
        scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "sold-cache-poller").apply { isDaemon = true }
        }
        scheduler!!.scheduleWithFixedDelay(
            { safePoll() },
            refreshIntervalMillis,     // first run after one interval (initial already done)
            refreshIntervalMillis,
            TimeUnit.MILLISECONDS
        )

        // Realtime listener for fast-path sale propagation.
        listenerRegistration = startRealtimeListener()
    }

    /** Clean shutdown — call at service exit. */
    fun stop() {
        log.info { "Stopping sold-EPC refresher" }
        listenerRegistration?.remove()
        listenerRegistration = null
        scheduler?.shutdown()
        try {
            scheduler?.awaitTermination(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        scheduler = null
    }

    /**
     * One synchronous full refresh. Public so main() can trigger a manual refresh
     * if ever useful (e.g. a "force refresh" button in the manager view later).
     */
    fun refreshNow() {
        val query: Query = db.collection("companies")
            .document(companyId)
            .collection("inventory_units")
            .whereEqualTo("status", "sold")

        val snapshot: QuerySnapshot = query.get().get()  // blocking
        val epcs = snapshot.documents.mapNotNull { it.getString("epc") ?: it.id }
        cache.replace(epcs)
    }

    /** Scheduled refresh — logs and swallows exceptions so the scheduler keeps running. */
    private fun safePoll() {
        try {
            val sizeBefore = cache.size
            refreshNow()
            val sizeAfter = cache.size
            val delta = sizeAfter - sizeBefore
            log.info {
                "Sold cache refreshed: $sizeAfter EPCs" +
                        if (delta != 0) " ($delta since last poll)" else ""
            }
        } catch (e: Exception) {
            log.error(e) { "Sold cache refresh failed - will retry in ${refreshIntervalMillis / 1000}s" }
        }
    }

    /**
     * Realtime listener on the same "status == sold" query.
     *
     * Firestore fires this callback whenever a document matching the query is
     * added, modified, or removed. We only care about ADDED/MODIFIED (new sales
     * or re-marked sold) — add the EPC to the cache. REMOVED events (unsell)
     * would call cache.remove(), reserved for a future unsell flow.
     *
     * Firebase SDK handles reconnection internally — if the connection drops,
     * it reconnects and replays missed events. We don't need our own retry.
     */
    private fun startRealtimeListener(): ListenerRegistration {
        val query = db.collection("companies")
            .document(companyId)
            .collection("inventory_units")
            .whereEqualTo("status", "sold")

        return query.addSnapshotListener(object : EventListener<QuerySnapshot> {
            override fun onEvent(snapshot: QuerySnapshot?, error: FirestoreException?) {
                if (error != null) {
                    log.warn { "Realtime sold listener error (will auto-reconnect): ${error.message}" }
                    return
                }
                if (snapshot == null) return

                // Only react to actual changes, not the initial full-snapshot replay
                // (that would duplicate work with our initial refreshNow() call).
                for (change in snapshot.documentChanges) {
                    when (change.type) {
                        com.google.cloud.firestore.DocumentChange.Type.ADDED,
                        com.google.cloud.firestore.DocumentChange.Type.MODIFIED -> {
                            val epc = epcFromDoc(change.document)
                            if (epc != null) {
                                cache.add(epc)
                                log.debug { "Realtime: added sold EPC $epc (cache size now ${cache.size})" }
                            }
                        }
                        com.google.cloud.firestore.DocumentChange.Type.REMOVED -> {
                            // Reserved for future unsell flow. For now a REMOVED
                            // event on this query means the item was deleted or
                            // its status changed away from "sold" — in either
                            // case the sold cache should drop it.
                            val epc = epcFromDoc(change.document)
                            if (epc != null) {
                                cache.remove(epc)
                                log.debug { "Realtime: removed EPC $epc from sold cache" }
                            }
                        }
                    }
                }
            }
        })
    }

    /**
     * Pulls the EPC out of a Firestore inventory_unit document. The epc field
     * mirrors the doc ID in our schema — fall back to the ID if the field is
     * missing (shouldn't happen but defensive).
     */
    private fun epcFromDoc(doc: QueryDocumentSnapshot): String? {
        return doc.getString("epc") ?: doc.id
    }
}