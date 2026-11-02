package com.snainfotech.tagscout.edge

import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.EventListener
import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreException
import com.google.cloud.firestore.ListenerRegistration
import com.google.cloud.firestore.SetOptions
import com.google.firebase.cloud.FirestoreClient
import com.snainfotech.tagscout.edge.llrp.FoundReader
import com.snainfotech.tagscout.edge.llrp.LlrpProbe
import com.snainfotech.tagscout.edge.llrp.NetworkScanner
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

const val EDGE_VERSION = "0.2.0"

/**
 * This PC's presence in TagScout (cloud mode).
 *
 *   /companies/{companyId}/edges/{edgeId}
 *     name, hostname, ips, version, mode, startedAt, lastSeenAt (heartbeat every 30 s)
 *     scanRequest { id, hosts }   ← written by the web (readersV1 "scan")
 *     scanStatus requested → scanning → done | error, scanFound, scanCompletedAt, scanHandledId
 *
 * A scan searches this PC's subnet(s) for LLRP readers (or checks the given
 * IPs) and writes each one to /companies/{companyId}/readers/{readerId} with
 * its model, MAC and antennas. Only device fields are written; what the web
 * sets (name, store, antenna roles, on/off) is never touched. Antennas of this
 * PC's readers are re-checked every 5 minutes so the web can warn about a
 * disconnected cable.
 */
class EdgeAgent(
    private val companyId: String,
    private val edgeId: String,
    private val edgeName: String,
    /** Hosts of this PC's readers, for the periodic antenna re-check. */
    private val knownReaderHosts: () -> List<String>,
) {
    private val db: Firestore = FirestoreClient.getFirestore()
    private val company = db.collection("companies").document(companyId)
    private val edgeRef: DocumentReference = company.collection("edges").document(edgeId)
    private val timers = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "edge-agent").apply { isDaemon = true } }
    private val scanner = Executors.newSingleThreadExecutor { r -> Thread(r, "edge-scan").apply { isDaemon = true } }
    private var listener: ListenerRegistration? = null
    @Volatile private var scanRunning = false

    fun start() {
        edgeRef.set(
            mapOf(
                "name" to edgeName, "hostname" to Config.computerName(), "ips" to localIps(), "version" to EDGE_VERSION,
                "mode" to "cloud", "startedAt" to FieldValue.serverTimestamp(), "lastSeenAt" to FieldValue.serverTimestamp(),
            ),
            SetOptions.merge(),
        ).get()
        log.info { "Registered as edge '$edgeId' ($edgeName) under companies/$companyId/edges" }

        timers.scheduleAtFixedRate({ heartbeat() }, 30, 30, TimeUnit.SECONDS)
        timers.scheduleAtFixedRate({ recheckAntennas() }, 2, 5, TimeUnit.MINUTES)

        listener = edgeRef.addSnapshotListener(object : EventListener<DocumentSnapshot> {
            override fun onEvent(snapshot: DocumentSnapshot?, error: FirestoreException?) {
                if (error != null) {
                    log.warn { "Edge listener error (will auto-reconnect): ${error.message}" }
                    return
                }
                if (snapshot != null && snapshot.exists()) onEdgeChanged(snapshot)
            }
        })
    }

    fun stop() {
        listener?.remove()
        timers.shutdownNow()
        scanner.shutdownNow()
    }

    private fun heartbeat() {
        try {
            edgeRef.set(mapOf("lastSeenAt" to FieldValue.serverTimestamp(), "ips" to localIps()), SetOptions.merge()).get()
        } catch (e: Exception) {
            log.warn { "Heartbeat failed (will retry): ${e.message}" }
        }
    }

    private fun onEdgeChanged(edge: DocumentSnapshot) {
        @Suppress("UNCHECKED_CAST")
        val request = edge.get("scanRequest") as? Map<String, Any?> ?: return
        val requestId = request["id"] as? String ?: return
        if (requestId == edge.getString("scanHandledId") || scanRunning) return
        val hosts = (request["hosts"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        scanRunning = true
        scanner.submit {
            try {
                runScan(requestId, hosts)
            } finally {
                scanRunning = false
            }
        }
    }

    private fun runScan(requestId: String, hosts: List<String>) {
        log.info { if (hosts.isEmpty()) "Scan requested: searching this PC's network" else "Scan requested: checking ${hosts.joinToString()}" }
        edgeRef.set(mapOf("scanStatus" to "scanning", "scanHandledId" to requestId, "scanError" to ""), SetOptions.merge()).get()
        try {
            val targets = hosts.ifEmpty { NetworkScanner.localCandidates() }
            val found = NetworkScanner.scan(targets)
            found.forEach { publish(it) }
            val readers = found.count { it.info != null }
            edgeRef.set(
                mapOf("scanStatus" to "done", "scanFound" to readers, "scanCompletedAt" to FieldValue.serverTimestamp()),
                SetOptions.merge(),
            ).get()
            log.info { "Scan finished: $readers reader(s) found${if (found.size > readers) ", ${found.size - readers} more answered but couldn't be read" else ""}" }
        } catch (e: Exception) {
            log.warn(e) { "Scan failed" }
            edgeRef.set(
                mapOf("scanStatus" to "error", "scanError" to (e.message ?: e.javaClass.simpleName), "scanCompletedAt" to FieldValue.serverTimestamp()),
                SetOptions.merge(),
            ).get()
        }
    }

    /** Re-probes this PC's readers so a pulled antenna cable shows on the web. */
    private fun recheckAntennas() {
        if (scanRunning) return
        for (host in knownReaderHosts().distinct()) {
            val found = try {
                FoundReader(host, LlrpProbe(host).probe(), null, null)
            } catch (e: Exception) {
                FoundReader(host, null, e.message ?: e.javaClass.simpleName, null)
            }
            try {
                publish(found, keepWebConsolePort = true)
            } catch (e: Exception) {
                log.warn { "Couldn't update reader $host: ${e.message}" }
            }
        }
    }

    /** Writes the device fields of one found reader (never the web's settings). */
    private fun publish(found: FoundReader, keepWebConsolePort: Boolean = false) {
        val info = found.info
        val readerId = readerIdFor(info?.readerId.orEmpty(), found.host)
        val fields = mutableMapOf<String, Any?>(
            "edgeId" to edgeId,
            "host" to found.host,
            "lastProbedAt" to FieldValue.serverTimestamp(),
            "probeError" to (found.error ?: ""),
        )
        if (!keepWebConsolePort) fields["webConsolePort"] = found.webConsolePort
        if (info != null) {
            fields += mapOf(
                "mac" to info.readerId,
                "manufacturer" to info.manufacturer,
                "manufacturerPen" to info.manufacturerPen,
                "modelId" to info.modelId,
                "firmware" to info.firmware,
                "maxAntennas" to info.maxAntennas,
                "antennas" to info.antennas.map { mapOf("port" to it.port, "connected" to it.connected) },
            )
        }
        company.collection("readers").document(readerId).set(fields, SetOptions.merge()).get()
        log.info {
            if (info != null) "Reader ${found.host} → $readerId: ${info.manufacturer} model ${info.modelId}, connected antennas ${info.connectedPorts}"
            else "Reader ${found.host} → $readerId: couldn't read details (${found.error})"
        }
    }

    companion object {
        /** Stable reader ID: from the MAC when the reader reports it, else from its IP. */
        fun readerIdFor(mac: String, host: String): String =
            "rdr-" + (mac.ifBlank { host }).replace(Regex("[^A-Za-z0-9]"), "-").trim('-').ifEmpty { "unknown" }

        fun localIps(): List<String> = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress } }
            .map { it.hostAddress }
            .distinct()
    }
}
