package com.snainfotech.tagscout.edge.llrp

import java.io.*
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * LlrpProbe against a fake LLRP reader that does the awkward things real ones
 * do: vendor (custom) parameters inside events, capabilities and antenna
 * lists, and keep-alives pushed in before each reply.
 */
class LlrpProbeTest {
    // TLV parameter: type (10 bits) + length (incl. 4-byte header) + body
    fun tlv(type: Int, body: ByteArray): ByteArray = ByteArrayOutputStream().also { o ->
        DataOutputStream(o).apply { writeShort(type); writeShort(4 + body.size); write(body) }
    }.toByteArray()
    fun bytes(vararg parts: ByteArray) = parts.fold(ByteArray(0)) { a, b -> a + b }
    fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    fun u32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    val status0 = tlv(287, bytes(u16(0), u16(0)))
    // Vendor extension: Custom param with PEN + subtype + junk — the kind of thing that broke LTKJava
    val vendorJunk = tlv(1023, bytes(u32(99999), u32(7), ByteArray(23) { (it * 37).toByte() }))

    fun startFake(busy: Boolean): Int {
        val server = ServerSocket(0)
        thread(isDaemon = true) { while (true) {
            val conn = try { server.accept() } catch (e: Exception) { break }
            conn.use { s ->
                val input = DataInputStream(BufferedInputStream(s.getInputStream()))
                val out = s.getOutputStream()
                val utc = tlv(128, ByteArray(8))
                val attempt = tlv(256, u16(if (busy) 2 else 0))
                try { out.write(Llrp.encode(63, 0, tlv(246, bytes(utc, vendorJunk, attempt)))); out.flush() } catch (e: IOException) { return@use }
                if (busy) return@use
                while (true) {
                    val msg = try { Llrp.read(input) } catch (e: IOException) { break }
                    out.write(Llrp.encode(62, 0)) // KEEPALIVE first, to be skipped
                    when (msg.type) {
                        1 -> {
                            val general = tlv(137, bytes(u16(4), u16(0xC000), u32(11111), u32(9010), u16(5), "1.2.3".toByteArray(),
                                tlv(139, bytes(u16(1), u16(0))), vendorJunk))
                            out.write(Llrp.encode(11, msg.id, bytes(status0, general, vendorJunk)))
                        }
                        2 -> {
                            val requested = msg.body[2].toInt()
                            val body = if (requested == 2) {
                                bytes(status0, *(1..4).map { p ->
                                    bytes(tlv(221, bytes(byteArrayOf(if (p <= 2) 0x80.toByte() else 0), u16(p), u16(600))), if (p == 2) vendorJunk else ByteArray(0))
                                }.toTypedArray())
                            } else {
                                bytes(status0, tlv(218, bytes(byteArrayOf(0), u16(6), byteArrayOf(0x00, 0x16, 0x25, 0x12, 0xAB.toByte(), 0xCD.toByte()))))
                            }
                            out.write(Llrp.encode(12, msg.id, body))
                        }
                        14 -> { out.write(Llrp.encode(4, msg.id, status0)); out.flush(); break }
                    }
                    out.flush()
                }
            }
        } }
        return server.localPort
    }

    @Test
    fun `identifies the reader and its connected antennas, skipping vendor blocks`() {
        val info = LlrpProbe("127.0.0.1", startFake(busy = false)).probe()
        assertEquals(4, info.maxAntennas)
        assertEquals(9010L, info.modelId)
        assertEquals(11111L, info.manufacturerPen)
        assertEquals("1.2.3", info.firmware)
        assertEquals(listOf(1, 2, 3, 4), info.antennas.map { it.port })
        assertEquals(listOf(1, 2), info.connectedPorts)
        assertEquals(600, info.antennas[0].gainCentiDbi)
        assertEquals("00162512ABCD", info.readerId)
    }

    @Test
    fun `a reader held by another client is reported busy`() {
        val result = runCatching { LlrpProbe("127.0.0.1", startFake(busy = true)).probe() }
        assertTrue(result.exceptionOrNull()?.message?.startsWith("busy") == true)
    }

    @Test
    fun `scan finds a reader among addresses and probes it`() {
        val port = startFake(busy = false)
        val found = NetworkScanner.scan(listOf("127.0.0.1", "192.0.2.1"), llrpPort = port, connectTimeoutMs = 300, webConsolePorts = emptyList())
        assertEquals(1, found.size)
        assertEquals(listOf(1, 2), found[0].info?.connectedPorts)
    }
}
