package com.snainfotech.tagscout.edge.llrp

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * A practice fixed reader for trying the web flow without hardware: answers
 * LLRP on this PC (port 5084 by default) as a 4-port reader with antennas on
 * ports 1 and 2. It doesn't read tags (tag reads come from a real reader's
 * web console), so once switched on in TagScout it shows "Can't connect".
 *
 *   .\gradlew.bat practiceReader                 then in TagScout → Door → Configure readers → check IP 127.0.0.1
 *   .\gradlew.bat practiceReader --args="5085"   on another port (scan won't find it there)
 */
fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: Llrp.PORT
    val server = try {
        ServerSocket(port)
    } catch (e: IOException) {
        println("Port $port is busy (another reader program?). Try: .\\gradlew.bat practiceReader --args=\"5085\"")
        return
    }
    println("Practice reader listening on port $port (4 antenna ports; 1 and 2 connected). Ctrl+C to stop.")
    println("In TagScout: Door → Configure readers → on your edge PC, check IP 127.0.0.1")
    while (true) {
        val socket = server.accept()
        thread(isDaemon = true) { socket.use { serve(it.getInputStream(), it.getOutputStream()) } }
    }
}

private fun tlv(type: Int, body: ByteArray): ByteArray = ByteArrayOutputStream().also { o ->
    DataOutputStream(o).apply { writeShort(type); writeShort(4 + body.size); write(body) }
}.toByteArray()

private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
private fun u32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
private fun cat(vararg parts: ByteArray) = parts.fold(ByteArray(0)) { a, b -> a + b }

private fun serve(inputStream: java.io.InputStream, out: java.io.OutputStream) {
    val input = DataInputStream(BufferedInputStream(inputStream))
    val ok = tlv(Llrp.LLRP_STATUS, cat(u16(0), u16(0)))
    try {
        out.write(Llrp.encode(Llrp.READER_EVENT_NOTIFICATION, 0, tlv(Llrp.READER_EVENT_NOTIFICATION_DATA, tlv(Llrp.CONNECTION_ATTEMPT_EVENT, u16(0)))))
        out.flush()
        while (true) {
            val msg = Llrp.read(input)
            val reply = when (msg.type) {
                Llrp.GET_READER_CAPABILITIES -> Llrp.encode(Llrp.GET_READER_CAPABILITIES_RESPONSE, msg.id, cat(ok,
                    tlv(Llrp.GENERAL_DEVICE_CAPABILITIES, cat(u16(4), u16(0x4000), u32(0), u32(901), u16(8), "practice".toByteArray()))))
                Llrp.GET_READER_CONFIG -> if (msg.body.size > 2 && msg.body[2].toInt() == 2) {
                    Llrp.encode(Llrp.GET_READER_CONFIG_RESPONSE, msg.id, cat(ok, *(1..4).map { p ->
                        tlv(Llrp.ANTENNA_PROPERTIES, cat(byteArrayOf(if (p <= 2) 0x80.toByte() else 0), u16(p), u16(0)))
                    }.toTypedArray()))
                } else {
                    Llrp.encode(Llrp.GET_READER_CONFIG_RESPONSE, msg.id, cat(ok,
                        tlv(Llrp.IDENTIFICATION, cat(byteArrayOf(0), u16(6), byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x01)))))
                }
                Llrp.CLOSE_CONNECTION -> Llrp.encode(Llrp.CLOSE_CONNECTION_RESPONSE, msg.id, ok)
                else -> null
            }
            if (reply != null) {
                out.write(reply)
                out.flush()
            }
            if (msg.type == Llrp.CLOSE_CONNECTION) return
        }
    } catch (_: IOException) {
        // client went away
    }
}
