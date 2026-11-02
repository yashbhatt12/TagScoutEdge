package com.snainfotech.tagscout.edge.llrp

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket

/** One antenna port and whether the reader senses an antenna on it. */
data class AntennaPort(val port: Int, val connected: Boolean, val gainCentiDbi: Int)

/** What a reader says about itself over LLRP. */
data class ReaderInfo(
    val host: String,
    val manufacturerPen: Long,
    val modelId: Long,
    val firmware: String,
    val maxAntennas: Int,
    val antennas: List<AntennaPort>,
    /** The reader's own ID (usually its MAC address), hex; blank if it doesn't say. */
    val readerId: String,
) {
    val manufacturer: String get() = MANUFACTURERS[manufacturerPen] ?: "PEN $manufacturerPen"
    val connectedPorts: List<Int> get() = antennas.filter { it.connected }.map { it.port }

    companion object {
        /** IANA enterprise numbers of common LLRP reader makers (only well-known ones). */
        val MANUFACTURERS = mapOf(25882L to "Impinj")
    }
}

class LlrpException(message: String) : Exception(message)

/**
 * A short, read-only LLRP session: connect, ask who the reader is and which
 * antennas are connected, close. It changes nothing on the reader and doesn't
 * start inventory, so it can run alongside the web-console tag reading.
 *
 * Note: an LLRP reader accepts one client connection at a time. If another
 * LLRP client (e.g. the vendor's sample app) is connected, the reader refuses
 * and this throws "busy".
 */
class LlrpProbe(private val host: String, private val port: Int = Llrp.PORT, private val timeoutMs: Int = 5000) {

    private var nextId = 100

    fun probe(): ReaderInfo {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = socket.getOutputStream()

            checkConnectionAccepted(input)

            val caps = request(input, output, Llrp.GET_READER_CAPABILITIES, Llrp.GET_READER_CAPABILITIES_RESPONSE,
                byteArrayOf(1)) // RequestedData 1 = General Device Capabilities
            val general = Llrp.params(caps).firstOrNull { it.type == Llrp.GENERAL_DEVICE_CAPABILITIES }
                ?: throw LlrpException("Reader sent no device capabilities")
            val g = general.body
            val maxAntennas = Llrp.u16(g, 0)
            val pen = Llrp.u32(g, 4)
            val model = Llrp.u32(g, 8)
            val fwLen = Llrp.u16(g, 12)
            val firmware = String(g, 14, fwLen.coerceAtMost(g.size - 14), Charsets.UTF_8)

            // GET_READER_CONFIG: AntennaID 0 (all), RequestedData 2 (AntennaProperties), GPI 0, GPO 0
            val antennaConfig = request(input, output, Llrp.GET_READER_CONFIG, Llrp.GET_READER_CONFIG_RESPONSE,
                byteArrayOf(0, 0, 2, 0, 0, 0, 0))
            val antennas = Llrp.params(antennaConfig)
                .filter { it.type == Llrp.ANTENNA_PROPERTIES && it.body.size >= 5 }
                .map { p ->
                    AntennaPort(
                        port = Llrp.u16(p.body, 1),
                        connected = (p.body[0].toInt() and 0x80) != 0,
                        gainCentiDbi = Llrp.u16(p.body, 3).toShort().toInt(),
                    )
                }
                .sortedBy { it.port }

            // GET_READER_CONFIG: RequestedData 1 (Identification)
            val identity = runCatching {
                val body = request(input, output, Llrp.GET_READER_CONFIG, Llrp.GET_READER_CONFIG_RESPONSE,
                    byteArrayOf(0, 0, 1, 0, 0, 0, 0))
                val p = Llrp.params(body).firstOrNull { it.type == Llrp.IDENTIFICATION }
                if (p == null || p.body.size < 3) "" else {
                    val count = Llrp.u16(p.body, 1)
                    p.body.copyOfRange(3, (3 + count).coerceAtMost(p.body.size)).joinToString("") { "%02X".format(it) }
                }
            }.getOrDefault("")

            runCatching {
                output.write(Llrp.encode(Llrp.CLOSE_CONNECTION, nextId++))
                output.flush()
                Llrp.read(input) // CLOSE_CONNECTION_RESPONSE (or anything); we're leaving either way
            }

            return ReaderInfo(host, pen, model, firmware, maxAntennas, antennas, identity)
        }
    }

    /**
     * The reader opens with READER_EVENT_NOTIFICATION carrying a
     * ConnectionAttemptEvent: 0 = accepted; anything else = another client
     * already holds the connection.
     */
    private fun checkConnectionAccepted(input: DataInputStream) {
        val first = Llrp.read(input)
        if (first.type != Llrp.READER_EVENT_NOTIFICATION) return // some readers skip it; carry on
        val data = Llrp.params(first.body).firstOrNull { it.type == Llrp.READER_EVENT_NOTIFICATION_DATA } ?: return
        val attempt = Llrp.params(data.body).firstOrNull { it.type == Llrp.CONNECTION_ATTEMPT_EVENT } ?: return
        if (attempt.body.size >= 2 && Llrp.u16(attempt.body, 0) != 0) {
            throw LlrpException("busy: another LLRP client is connected to this reader")
        }
    }

    /** Sends one request and returns the body of its response, skipping unrelated messages (events, keepalives). */
    private fun request(input: DataInputStream, output: java.io.OutputStream, type: Int, responseType: Int, body: ByteArray): ByteArray {
        val id = nextId++
        output.write(Llrp.encode(type, id, body))
        output.flush()
        repeat(50) {
            val msg = Llrp.read(input)
            if (msg.type == Llrp.ERROR_MESSAGE) {
                val (code, text) = Llrp.status(msg.body)
                throw LlrpException("Reader error $code: $text")
            }
            if (msg.type == responseType && msg.id == id) {
                val (code, text) = Llrp.status(msg.body)
                if (code != 0) throw LlrpException("Reader refused (status $code): $text")
                return msg.body
            }
        }
        throw LlrpException("No response to message type $type")
    }
}
