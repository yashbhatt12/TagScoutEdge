package com.snainfotech.tagscout.edge

import com.google.cloud.firestore.EventListener
import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreException
import com.google.cloud.firestore.ListenerRegistration
import com.google.cloud.firestore.QuerySnapshot
import com.google.cloud.firestore.SetOptions
import com.google.firebase.cloud.FirestoreClient
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private val log = KotlinLogging.logger {}

/**
 * Runs one reading pipeline for every reader of this edge PC that is switched
 * on in TagScout (readers where edgeId == this PC and enabled == true), and
 * stops pipelines when a reader is switched off, removed or moved.
 *
 * Each reader's status goes back to its document for the web:
 *   connection: connecting | connected | error | stopped, connectionError, lastReadAt (≈ once a minute)
 */
class ReaderManager(
    companyId: String,
    private val edgeId: String,
    private val username: String,
    private val password: String,
    private val pollMs: Long,
    private val dedupWindowMs: Long,
    private val flushIntervalMs: Long,
    /** Called for every closed TagEvent, with the reader it came from. */
    private val onEvent: (TagEvent, String) -> Unit,
    /** Called for every raw read (for the status line). */
    private val onRawRead: () -> Unit = {},
) {
    private val db: Firestore = FirestoreClient.getFirestore()
    private val readers = db.collection("companies").document(companyId).collection("readers")
    private val pipelines = ConcurrentHashMap<String, ReaderPipeline>()
    private val hosts = ConcurrentHashMap<String, String>()
    private var listener: ListenerRegistration? = null

    /** Hosts of every reader of this PC (on or off), for the antenna re-check. */
    fun knownHosts(): List<String> = hosts.values.toList()
    val running: Int get() = pipelines.size

    fun start() {
        listener = readers.whereEqualTo("edgeId", edgeId).addSnapshotListener(object : EventListener<QuerySnapshot> {
            override fun onEvent(snapshot: QuerySnapshot?, error: FirestoreException?) {
                if (error != null) {
                    log.warn { "Readers listener error (will auto-reconnect): ${error.message}" }
                    return
                }
                if (snapshot != null) reconcile(snapshot)
            }
        })
        log.info { "Watching TagScout for readers assigned to edge '$edgeId'" }
    }

    @Synchronized
    private fun reconcile(snapshot: QuerySnapshot) {
        val wanted = mutableMapOf<String, Pair<String, Int?>>()
        hosts.clear()
        for (doc in snapshot.documents) {
            val host = doc.getString("host") ?: continue
            hosts[doc.id] = host
            if (doc.getBoolean("enabled") == true) {
                wanted[doc.id] = host to doc.getLong("webConsolePort")?.toInt()
            }
        }
        // Stop what's no longer wanted, or whose address changed.
        for ((id, pipeline) in pipelines.entries.toList()) {
            val want = wanted[id]
            if (want == null || want.first != pipeline.host || want.second != pipeline.webConsolePort) {
                log.info { "Stopping reader $id (${pipeline.host})" }
                pipeline.stop()
                pipelines.remove(id)
                if (want == null) status(id, "stopped", "")
            }
        }
        // Start what's newly wanted.
        for ((id, want) in wanted) {
            if (pipelines.containsKey(id)) continue
            log.info { "Starting reader $id (${want.first})" }
            val pipeline = ReaderPipeline(
                readerId = id, host = want.first, webConsolePort = want.second, username = username, password = password,
                pollMs = pollMs, dedupWindowMs = dedupWindowMs, flushIntervalMs = flushIntervalMs,
                onEvent = { onEvent(it, id) },
                onRawRead = onRawRead,
                onStatus = { state, error -> status(id, state, error) },
                onReadSeen = { lastRead(id) },
            )
            pipelines[id] = pipeline
            pipeline.start()
        }
    }

    private val lastReadWrites = ConcurrentHashMap<String, Long>()

    private fun lastRead(id: String) {
        val now = System.currentTimeMillis()
        val last = lastReadWrites[id] ?: 0
        if (now - last < 60_000) return
        lastReadWrites[id] = now
        runCatching { readers.document(id).set(mapOf("lastReadAt" to FieldValue.serverTimestamp()), SetOptions.merge()) }
    }

    private fun status(id: String, state: String, error: String) {
        runCatching {
            readers.document(id).set(
                mapOf("connection" to state, "connectionError" to error, "connectionAt" to FieldValue.serverTimestamp()),
                SetOptions.merge(),
            )
        }.onFailure { log.warn { "Couldn't update status of $id: ${it.message}" } }
    }

    fun stop() {
        listener?.remove()
        pipelines.values.forEach { it.stop() }
        pipelines.clear()
    }
}

/**
 * One reader: the web-console client (tag reads), de-duplication into
 * TagEvents, and a reconnect loop (5 s, doubling to 60 s) if the reader drops
 * or can't be reached.
 */
class ReaderPipeline(
    val readerId: String,
    val host: String,
    val webConsolePort: Int?,
    private val username: String,
    private val password: String,
    private val pollMs: Long,
    dedupWindowMs: Long,
    private val flushIntervalMs: Long,
    private val onEvent: (TagEvent) -> Unit,
    private val onRawRead: () -> Unit,
    private val onStatus: (String, String) -> Unit,
    private val onReadSeen: () -> Unit,
) {
    private val dedup = Deduplicator(windowMillis = dedupWindowMs)
    private val running = AtomicBoolean(false)
    private val reads = AtomicLong(0)
    private var flusher: ScheduledExecutorService? = null
    private var worker: Thread? = null
    @Volatile private var client: WebSocketReaderClient? = null

    /** The reader's web console: wss on 7681 (TLS) unless only the plain port 8000 answered. */
    private val url: String get() = if (webConsolePort == 8000) "ws://$host:8000" else "wss://$host:${webConsolePort ?: 7681}"

    fun start() {
        if (!running.compareAndSet(false, true)) return
        flusher = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "flush-$readerId").apply { isDaemon = true } }.also {
            it.scheduleAtFixedRate({
                try {
                    dedup.flush().forEach(onEvent)
                } catch (e: Exception) {
                    log.warn(e) { "Flush failed for $readerId (continuing)" }
                }
            }, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS)
        }
        worker = Thread({ loop() }, "reader-$readerId").apply { isDaemon = true; start() }
    }

    private fun loop() {
        var backoff = 5_000L
        while (running.get()) {
            onStatus("connecting", "")
            val c = WebSocketReaderClient(
                readerUrl = url, username = username, password = password, pollIntervalMillis = pollMs,
                onRead = { raw ->
                    reads.incrementAndGet()
                    onRawRead()
                    onReadSeen()
                    dedup.offer(raw)?.let(onEvent)
                },
            )
            client = c
            try {
                c.connectAndLogin()
                c.startInventory()
                onStatus("connected", "")
                log.info { "Reader $readerId reading on $url" }
                backoff = 5_000L
                while (running.get() && c.isOpen) Thread.sleep(1_000)
                if (running.get()) log.warn { "Reader $readerId disconnected; reconnecting" }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                log.warn { "Reader $readerId ($url): ${e.message}" }
                onStatus("error", e.message ?: e.javaClass.simpleName)
            } finally {
                runCatching { c.close() }
            }
            if (!running.get()) break
            try {
                Thread.sleep(backoff)
            } catch (e: InterruptedException) {
                break
            }
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        worker?.interrupt()
        runCatching { client?.close() }
        flusher?.shutdownNow()
        runCatching { dedup.flushAll().forEach(onEvent) }
    }
}
