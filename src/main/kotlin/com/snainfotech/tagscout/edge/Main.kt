package com.snainfotech.tagscout.edge

import ch.qos.logback.classic.Level as LogbackLevel
import ch.qos.logback.classic.Logger as LogbackLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import org.slf4j.Logger as Slf4jLogger
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private val log = KotlinLogging.logger("TagScoutEdge")

// ── Runtime stats ────────────────────────────────────────────────────
private val stats = PipelineStats()

/**
 * TagScoutEdge — jewellery portal service.
 *
 * Pipeline:
 *   FR901 reader (WebSocket)
 *     → RawTagRead
 *     → Deduplicator (configurable collapse window)
 *     → TagEvent (one per EPC per burst)
 *     → sold-cache lookup → ALARM / ALLOW decision
 *     → Firestore audit write
 *
 * Configuration comes from config/edge-service.properties plus
 * secrets/reader-credentials.properties. See the matching .example files
 * for format. Service runs until Ctrl+C.
 */
fun main() {
    silenceVerboseLogging()

    // 0. Load config FIRST. If this fails, we exit immediately with a
    //    message the operator can act on, before any Firebase or reader
    //    connection is attempted.
    val config = try {
        Config.load()
    } catch (e: ConfigException) {
        System.err.println()
        System.err.println("═══ CONFIGURATION ERROR ═══")
        System.err.println(e.message)
        System.err.println("═══════════════════════════")
        System.err.println()
        System.exit(1)
        return  // unreachable; keeps the compiler happy
    }

    // Apply operator-chosen log level to the root logger. The library-silencer
    // above sets specific noisy loggers to WARN regardless — that's intentional.
    applyLogLevel(config.logLevel)

    banner(config)
    log.info { config.describe() }

    // 1. Firestore — single long-lived instance per JVM.
    val firestore = FirestoreWriter.getInstance(
        serviceAccountPath = config.firebaseServiceAccountPath,
        companyId = config.firebaseCompanyId,
        readerId = config.readerId
    )
    log.info { "Firestore writer ready (company=${config.firebaseCompanyId}, reader=${config.readerId})" }

    // 2. Sold-EPC cache + refresher.
    //    start() blocks on the initial refresh so the cache is warm before
    //    the first portal read.
    val soldCache = SoldEpcCache()
    val refresher = SoldEpcRefresher(
        cache = soldCache,
        companyId = config.firebaseCompanyId,
        refreshIntervalMillis = config.soldRefreshMs
    )
    refresher.start()
    log.info { "Sold-EPC cache initialized (${soldCache.size} EPCs loaded)" }

    // 3. Deduplicator — raw reads in, TagEvents out.
    val dedup = Deduplicator(windowMillis = config.dedupWindowMs)

    // 4. WebSocket client — callback feeds the dedup.
    //    offer() may return a TagEvent if a prior window closed because this
    //    read arrived outside the dedup window. Handle it inline; the flush
    //    thread catches everything else.
    val wsClient = WebSocketReaderClient(
        readerUrl = config.readerUrl,
        username = config.readerUsername,
        password = config.readerPassword,
        pollIntervalMillis = config.readerPollMs,
        onRead = { raw ->
            stats.rawReads.incrementAndGet()
            // TODO: apply config.rssiThresholdDbm filter here once we tune per-install.
            // For now rssi.threshold.dbm is loaded but not enforced.
            val closedByOffer = dedup.offer(raw)
            if (closedByOffer != null) {
                decide(closedByOffer, soldCache, firestore)
            }
        }
    )

    // 5. Periodic flusher — pulls idle-timeout TagEvents and runs them through the decider.
    val flusher = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "dedup-flusher").apply { isDaemon = true }
    }
    flusher.scheduleAtFixedRate(
        {
            try {
                val events = dedup.flush()
                for (event in events) decide(event, soldCache, firestore)
            } catch (e: Exception) {
                log.warn(e) { "Flush iteration failed (continuing)" }
            }
        },
        config.flushIntervalMs,
        config.flushIntervalMs,
        TimeUnit.MILLISECONDS
    )

    // 6. Periodic status line.
    val statusReporter = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "status-reporter").apply { isDaemon = true }
    }
    statusReporter.scheduleAtFixedRate(
        { log.info { stats.summary(soldCacheSize = soldCache.size, soldCacheAgeMs = soldCache.ageMillis()) } },
        10, 10, TimeUnit.SECONDS
    )

    // 7. Shutdown hook.
    Runtime.getRuntime().addShutdownHook(Thread {
        log.info { "" }
        log.info { "═══ SHUTDOWN ═══" }
        try { flusher.shutdown() } catch (_: Exception) {}
        try { statusReporter.shutdown() } catch (_: Exception) {}
        try {
            val finalEvents = dedup.flushAll()
            if (finalEvents.isNotEmpty()) {
                log.info { "Flushing ${finalEvents.size} final TagEvent(s)" }
                finalEvents.forEach { decide(it, soldCache, firestore) }
            }
        } catch (e: Exception) {
            log.warn { "Final flush failed: ${e.message}" }
        }
        try { refresher.stop() } catch (_: Exception) {}
        try { wsClient.close() } catch (_: Exception) {}
        log.info { stats.finalSummary() }
        log.info { "═════════════════" }
    })

    // 8. Connect, log in, start inventory, block forever.
    try {
        wsClient.connectAndLogin()
        wsClient.startInventory()
        log.info { "" }
        log.info { "═══════════════════════════════════════════════" }
        log.info { "  PORTAL SERVICE RUNNING" }
        log.info { "  Watching for tags on ${config.readerUrl}" }
        log.info { "  Ctrl+C to stop" }
        log.info { "═══════════════════════════════════════════════" }
        log.info { "" }
        Thread.currentThread().join()  // Block until shutdown.
    } catch (e: InterruptedException) {
        log.info { "Main thread interrupted — shutting down" }
    } catch (e: Exception) {
        log.error(e) { "Fatal error — service exiting" }
    }
}

/**
 * Decision: look up EPC in the sold cache, log loudly, write audit.
 */
private fun decide(
    event: TagEvent,
    soldCache: SoldEpcCache,
    firestore: FirestoreWriter
) {
    val isSold = soldCache.isSold(event.epc)
    val decision = if (isSold) "✓ ALLOW" else "🚨 ALARM"
    if (isSold) stats.allows.incrementAndGet() else stats.alarms.incrementAndGet()
    stats.tagEvents.incrementAndGet()

    log.info {
        "$decision  epc=${event.epc}  ant=${event.antennaPort}  " +
                "rssi=${event.rssi}dBm  dwell=${event.dwellMillis}ms  reads=${event.rawReadCount}"
    }

    try {
        firestore.write(event)
    } catch (e: Exception) {
        log.warn { "Firestore write failed for epc=${event.epc}: ${e.message}" }
    }
}

private fun banner(config: Config) {
    log.info { "═══════════════════════════════════════════════" }
    log.info { "  TagScoutEdge  —  Jewellery Portal Service" }
    log.info { "═══════════════════════════════════════════════" }
}

/**
 * Set the user-facing (root) log level based on config. Library loggers
 * stay at WARN via silenceVerboseLogging() — those are tuned for signal
 * clarity, not operator preference.
 */
private fun applyLogLevel(level: String) {
    val lb = LoggerFactory.getLogger(Slf4jLogger.ROOT_LOGGER_NAME) as? LogbackLogger ?: return
    lb.level = when (level) {
        "TRACE" -> LogbackLevel.TRACE
        "DEBUG" -> LogbackLevel.DEBUG
        "INFO" -> LogbackLevel.INFO
        "WARN" -> LogbackLevel.WARN
        "ERROR" -> LogbackLevel.ERROR
        else -> LogbackLevel.INFO
    }
}

/** Suppress chatty library logging so decision lines stand out. */
private fun silenceVerboseLogging() {
    val loggers = listOf(
        "io.grpc", "io.netty", "io.perfmark",
        "com.google.firebase", "com.google.cloud",
        "org.java_websocket", "org.java-websocket",
        "com.snainfotech.tagscout.edge.WebSocketReaderClient"
    )
    for (name in loggers) {
        try {
            (LoggerFactory.getLogger(name) as LogbackLogger).level = LogbackLevel.WARN
        } catch (_: Exception) { /* not logback */ }
    }
}

/** Simple counters for the periodic status line and shutdown summary. */
private class PipelineStats {
    val rawReads = AtomicLong(0)
    val tagEvents = AtomicLong(0)
    val alarms = AtomicLong(0)
    val allows = AtomicLong(0)

    fun summary(soldCacheSize: Int, soldCacheAgeMs: Long): String {
        val ageSec = soldCacheAgeMs / 1000
        return "status: rawReads=${rawReads.get()}  tagEvents=${tagEvents.get()}  " +
                "alarms=${alarms.get()}  allows=${allows.get()}  " +
                "soldCache=$soldCacheSize epcs (age ${ageSec}s)"
    }

    fun finalSummary(): String =
        "lifetime: rawReads=${rawReads.get()}  tagEvents=${tagEvents.get()}  " +
                "alarms=${alarms.get()}  allows=${allows.get()}"
}