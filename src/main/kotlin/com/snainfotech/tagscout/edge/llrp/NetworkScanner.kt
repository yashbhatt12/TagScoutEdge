package com.snainfotech.tagscout.edge.llrp

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A device that answered on the LLRP port, with what it told us (or why it couldn't). */
data class FoundReader(
    val host: String,
    val info: ReaderInfo?,
    val error: String?,
    /** Whether the reader's web console (used for tag reads) answers too. */
    val webConsolePort: Int?,
)

/**
 * Finds LLRP readers on the edge PC's own networks: every IPv4 address of
 * each active, non-loopback interface, scanning its subnet (at most the /24
 * around the PC — a whole /16 would take too long) for TCP port 5084, then
 * asking each one who it is over LLRP.
 */
object NetworkScanner {

    /** The addresses to try: the /24 (or smaller) subnet of each local IPv4 address. */
    fun localCandidates(): List<String> {
        val hosts = linkedSetOf<String>()
        for (nic in NetworkInterface.getNetworkInterfaces().toList()) {
            if (!nic.isUp || nic.isLoopback || nic.isVirtual) continue
            for (addr in nic.interfaceAddresses) {
                val ip = addr.address as? Inet4Address ?: continue
                if (!ip.isSiteLocalAddress) continue
                val prefix = addr.networkPrefixLength.toInt().coerceIn(24, 30)
                val base = ip.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
                val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
                val network = base and mask
                val size = 1L shl (32 - prefix)
                for (h in 1 until size - 1) {
                    val v = network + h
                    if (v == base) continue
                    hosts += "${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}.${v and 0xFF}"
                }
            }
        }
        return hosts.toList()
    }

    fun isOpen(host: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
    } catch (_: Exception) {
        false
    }

    /**
     * Scans [hosts] (default: the local subnets) and probes every device that
     * answers on [llrpPort]. [onProgress] gets (checked, total).
     */
    fun scan(
        hosts: List<String> = localCandidates(),
        llrpPort: Int = Llrp.PORT,
        connectTimeoutMs: Int = 400,
        webConsolePorts: List<Int> = listOf(7681, 8000),
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<FoundReader> {
        val pool = Executors.newFixedThreadPool(64)
        try {
            var done = 0
            val open = pool.invokeAll(hosts.map { h ->
                Callable { (if (isOpen(h, llrpPort, connectTimeoutMs)) h else null).also { synchronized(this) { onProgress(++done, hosts.size) } } }
            }).mapNotNull { it.get() }

            return open.map { host ->
                val web = webConsolePorts.firstOrNull { isOpen(host, it, connectTimeoutMs) }
                try {
                    FoundReader(host, LlrpProbe(host, llrpPort).probe(), null, web)
                } catch (e: Exception) {
                    FoundReader(host, null, e.message ?: e.javaClass.simpleName, web)
                }
            }.sortedBy { r -> r.host.split(".").fold(0L) { acc, p -> acc * 256 + p.toLong() } }
        } finally {
            pool.shutdown()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
