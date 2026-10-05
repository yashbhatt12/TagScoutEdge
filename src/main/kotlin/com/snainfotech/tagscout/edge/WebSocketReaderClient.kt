package com.snainfotech.tagscout.edge

import io.github.oshai.kotlinlogging.KotlinLogging
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private val log = KotlinLogging.logger {}

/**
 * WebSocket client for the FR901 reader's native web API.
 *
 * Protocol (reverse-engineered from the TSC web console):
 *   wss://192.168.1.111:7681 (TLS) or ws://192.168.1.111:8000 (plain)
 *   1. REQUEST LOGIN_TABLE (handshake — reader expects this before accepting admin login)
 *   2. ACTION LOGIN { id, password }
 *   3. REQUEST READ_TAGS (page context)
 *   4. ACTION START_INVENTORY
 *   5. ACTION QUERY_READTAGS (poll every ~200ms)
 *   6. ACTION STOP_INVENTORY
 *
 * Each parsed tag entry becomes one RawTagRead delivered to the onRead callback.
 *
 * TLS handling: the FR901 uses a self-signed cert whose SAN doesn't match its IP.
 * We trust it unconditionally via a trust-all TrustManager AND disable hostname
 * verification via onSetSSLParameters().
 */
class WebSocketReaderClient(
    private val readerUrl: String,
    private val username: String,
    private val password: String,
    private val pollIntervalMillis: Long = 200L,
    private val onRead: (RawTagRead) -> Unit
) {

    private var ws: WebSocketClient? = null
    private var poller: ScheduledExecutorService? = null
    private val isConnected = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)

    // Latch used to make the async login handshake blocking from the caller's view.
    private var loginLatch: CountDownLatch? = null
    private var loginResult: String? = null

    /**
     * Blocking connect + login. Returns when the reader has accepted the admin login.
     * Throws if the reader is unreachable, TLS fails, or login is rejected.
     */
    fun connectAndLogin(timeoutMs: Long = 10_000L) {
        log.info { "Connecting to reader WebSocket at $readerUrl" }
        loginLatch = CountDownLatch(1)

        val client = object : WebSocketClient(URI(readerUrl)) {
            override fun onSetSSLParameters(sslParameters: SSLParameters) {
                sslParameters.endpointIdentificationAlgorithm = null
            }

            override fun onOpen(handshakedata: ServerHandshake?) {
                log.info { "WebSocket connection open (HTTP ${handshakedata?.httpStatus})" }
                isConnected.set(true)
                // Step 1 of the handshake: request LOGIN_TABLE. The reader won't
                // accept admin credentials unless this precursor is sent first.
                // LOGIN itself is sent in handleLoginTableResponse() once this returns.
                send("""{"type":"REQUEST","text":"LOGIN_TABLE"}""")
                log.debug { "Sent LOGIN_TABLE request" }
            }

            override fun onMessage(message: String?) {
                if (message == null) return
                handleMessage(message)
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                log.warn { "WebSocket closed: code=$code reason=$reason remote=$remote" }
                isConnected.set(false)
                isRunning.set(false)
            }

            override fun onError(ex: Exception?) {
                log.error(ex) { "WebSocket error" }
            }
        }

        // Trust-all SSL for the self-signed reader cert.
        if (readerUrl.startsWith("wss://")) {
            client.setSocketFactory(trustAllSSLContext().socketFactory)
        }

        // Assign ws BEFORE connecting so handleLoginTableResponse can send through it.
        ws = client

        val opened = client.connectBlocking(timeoutMs, TimeUnit.MILLISECONDS)
        if (!opened) {
            throw IllegalStateException("Could not open WebSocket to $readerUrl within ${timeoutMs}ms")
        }

        val loggedIn = loginLatch?.await(timeoutMs, TimeUnit.MILLISECONDS) ?: false
        if (!loggedIn) {
            throw IllegalStateException("Login response not received within ${timeoutMs}ms")
        }
        val result = loginResult
        if (result != "admin_success" && result != "success") {
            throw IllegalStateException("Reader rejected login (result=$result)")
        }
        log.info { "Logged in as $username (result=$result)" }
    }

    /** Starts inventory on the reader and begins polling every pollIntervalMillis. */
    fun startInventory() {
        require(isConnected.get()) { "Not connected — call connectAndLogin() first" }

        ws?.send("""{"type":"REQUEST","text":"READ_TAGS"}""")
        ws?.send("""{"type":"ACTION","text":"START_INVENTORY"}""")
        log.info { "Sent START_INVENTORY" }
        isRunning.set(true)

        poller = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ws-reader-poller").apply { isDaemon = true }
        }
        poller!!.scheduleAtFixedRate(
            { if (isRunning.get() && isConnected.get()) ws?.send("""{"type":"ACTION","text":"QUERY_READTAGS"}""") },
            pollIntervalMillis,
            pollIntervalMillis,
            TimeUnit.MILLISECONDS
        )
        log.info { "Polling QUERY_READTAGS every ${pollIntervalMillis}ms" }
    }

    /** Stops inventory and polling. Leaves the WebSocket open. */
    fun stopInventory() {
        isRunning.set(false)
        poller?.shutdown()
        poller = null
        ws?.send("""{"type":"ACTION","text":"STOP_INVENTORY"}""")
        log.info { "Sent STOP_INVENTORY" }
    }

    /** Full shutdown — stops inventory and closes the WebSocket. */
    fun close() {
        try {
            if (isRunning.get()) stopInventory()
        } catch (e: Exception) {
            log.warn { "Error stopping inventory: ${e.message}" }
        }
        try {
            ws?.closeBlocking()
        } catch (e: Exception) {
            log.warn { "Error closing WebSocket: ${e.message}" }
        }
        isConnected.set(false)
    }

    // ── Private: message handling ────────────────────────────────────

    private fun handleMessage(message: String) {
        when {
            """"text":"LOGIN_TABLE"""" in message || """"text": "LOGIN_TABLE"""" in message -> handleLoginTableResponse(message)
            """"text":"LOGIN"""" in message || """"text": "LOGIN"""" in message -> handleLoginResponse(message)
            """"text":"QUERY_READTAGS"""" in message || """"text": "QUERY_READTAGS"""" in message -> handleQueryResponse(message)
            """"text":"START_INVENTORY"""" in message || """"text": "START_INVENTORY"""" in message ->
                log.info { "Reader confirmed START_INVENTORY" }
            """"text":"STOP_INVENTORY"""" in message || """"text": "STOP_INVENTORY"""" in message ->
                log.info { "Reader confirmed STOP_INVENTORY" }
            else -> log.debug { "Unhandled message: ${message.take(120)}" }
        }
    }

    /**
     * Step 2 of login handshake: now that LOGIN_TABLE has responded, send the
     * actual LOGIN with id + password. The reader will respond with LOGIN /
     * admin_success (or guest_success / fail), handled by handleLoginResponse.
     */
    private fun handleLoginTableResponse(message: String) {
        log.debug { "Received LOGIN_TABLE response; sending LOGIN with credentials" }
        val loginMsg = """{"type":"ACTION","text":"LOGIN","id":"$username","password":"$password"}"""
        ws?.send(loginMsg)
    }

    private fun handleLoginResponse(message: String) {
        loginResult = extractJsonString(message, "result")
        loginLatch?.countDown()
    }

    private fun handleQueryResponse(message: String) {
        val tagField = extractJsonString(message, "tag") ?: return
        if (tagField.isBlank()) return

        val now = System.currentTimeMillis()
        var emitted = 0
        for (entry in tagField.split(",")) {
            val parts = entry.split(";")
            if (parts.size < 4) continue
            try {
                val epc = parts[0].trim().uppercase()
                // parts[1] is SeenCount — not used for dedup.
                val rssi = parts[2].trim().toInt()
                val antennaPort = parts[3].trim().toInt()
                // parts[4] FirstSeenTime — not used for dedup; we stamp with 'now'.
                onRead(
                    RawTagRead(
                        epc = epc,
                        antennaPort = antennaPort,
                        rssi = rssi,
                        readAtMillis = now
                    )
                )
                emitted++
            } catch (e: Exception) {
                log.warn { "Failed to parse tag entry '$entry': ${e.message}" }
            }
        }
        if (emitted > 0) log.debug { "Query response → $emitted RawTagReads" }
    }

    /**
     * Shallow string-value extractor for the reader's simple JSON. The reader
     * emits well-formed one-level objects; a full parser is overkill.
     */
    private fun extractJsonString(message: String, key: String): String? {
        val needle = """"$key""""
        val keyIdx = message.indexOf(needle)
        if (keyIdx < 0) return null
        val afterKey = keyIdx + needle.length
        val colonIdx = message.indexOf(':', afterKey)
        if (colonIdx < 0) return null
        val openQuote = message.indexOf('"', colonIdx + 1)
        if (openQuote < 0) return null
        val closeQuote = message.indexOf('"', openQuote + 1)
        if (closeQuote < 0) return null
        return message.substring(openQuote + 1, closeQuote)
    }

    /** SSLContext that trusts all certificates. Scoped to this one WebSocket. */
    private fun trustAllSSLContext(): SSLContext {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, SecureRandom())
        return ctx
    }
}