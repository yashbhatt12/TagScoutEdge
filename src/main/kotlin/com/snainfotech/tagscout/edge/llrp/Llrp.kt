package com.snainfotech.tagscout.edge.llrp

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

/**
 * Just enough LLRP (EPCglobal Low Level Reader Protocol 1.0.1) to identify a
 * reader and its antennas — written by hand instead of LTKJava, because
 * LTKJava's decoder breaks on the FR901's vendor extensions (see the commit
 * that moved tag reads to the web console). Here every message and parameter
 * is length-prefixed, so anything we don't understand — custom parameters
 * (type 1023), unknown TLVs — is simply stepped over.
 *
 * Message header (10 bytes): 3 bits reserved | 3 bits version (1) | 10 bits type,
 * 32-bit total length, 32-bit message ID. Parameters are TLV (16-bit type with
 * the top 6 bits reserved, 16-bit length including the 4-byte header) or TV
 * (one byte with the top bit set, then a fixed-size value).
 */
object Llrp {
    const val PORT = 5084

    // Message types
    const val GET_READER_CAPABILITIES = 1
    const val GET_READER_CONFIG = 2
    const val CLOSE_CONNECTION = 14
    const val CLOSE_CONNECTION_RESPONSE = 4
    const val GET_READER_CAPABILITIES_RESPONSE = 11
    const val GET_READER_CONFIG_RESPONSE = 12
    const val READER_EVENT_NOTIFICATION = 63
    const val ERROR_MESSAGE = 100

    // Parameter types
    const val GENERAL_DEVICE_CAPABILITIES = 137
    const val IDENTIFICATION = 218
    const val ANTENNA_PROPERTIES = 221
    const val READER_EVENT_NOTIFICATION_DATA = 246
    const val CONNECTION_ATTEMPT_EVENT = 256
    const val LLRP_STATUS = 287
    const val CUSTOM = 1023

    /** Sizes (including the type byte) of LLRP 1.0.1 TV parameters, so they can be skipped. */
    private val TV_SIZE = mapOf(
        1 to 3, 2 to 9, 3 to 9, 4 to 9, 5 to 9, 6 to 2, 7 to 3, 8 to 3, 9 to 5, 10 to 3,
        11 to 3, 12 to 3, 13 to 13, 14 to 3, 15 to 3, 16 to 5, 17 to 3, 18 to 5, 19 to 3, 20 to 3,
    )

    class Message(val type: Int, val id: Int, val body: ByteArray)
    class Param(val type: Int, val body: ByteArray)

    fun encode(type: Int, id: Int, body: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeShort((1 shl 10) or type) // version 1
            writeInt(10 + body.size)
            writeInt(id)
            write(body)
        }
        return out.toByteArray()
    }

    /** Reads one whole message; throws on EOF or a nonsensical length. */
    fun read(input: DataInputStream): Message {
        val typeField = input.readUnsignedShort()
        val length = input.readInt()
        val id = input.readInt()
        require(length in 10..(16 * 1024 * 1024)) { "Bad LLRP message length $length" }
        val body = ByteArray(length - 10)
        input.readFully(body)
        return Message(typeField and 0x3FF, id, body)
    }

    /**
     * The parameters in [bytes] from [from], stepping over anything unknown.
     * Stops quietly at an unknown TV parameter (its size can't be known).
     */
    fun params(bytes: ByteArray, from: Int = 0): List<Param> {
        val out = mutableListOf<Param>()
        var i = from
        while (i < bytes.size) {
            val first = bytes[i].toInt() and 0xFF
            if (first and 0x80 != 0) { // TV
                val type = first and 0x7F
                val size = TV_SIZE[type] ?: break
                if (i + size > bytes.size) break
                out += Param(type, bytes.copyOfRange(i + 1, i + size))
                i += size
            } else { // TLV
                if (i + 4 > bytes.size) break
                val type = ((bytes[i].toInt() and 0x03) shl 8) or (bytes[i + 1].toInt() and 0xFF)
                val length = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
                if (length < 4 || i + length > bytes.size) break
                out += Param(type, bytes.copyOfRange(i + 4, i + length))
                i += length
            }
        }
        return out
    }

    fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
    fun u32(b: ByteArray, at: Int) = ByteBuffer.wrap(b, at, 4).int.toLong() and 0xFFFFFFFFL

    /** LLRPStatus: (code, description). Code 0 = M_Success. */
    fun status(body: ByteArray): Pair<Int, String> {
        val p = params(body).firstOrNull { it.type == LLRP_STATUS } ?: return 0 to ""
        val code = u16(p.body, 0)
        val len = if (p.body.size >= 4) u16(p.body, 2) else 0
        val text = if (len > 0 && 4 + len <= p.body.size) String(p.body, 4, len, Charsets.UTF_8) else ""
        return code to text
    }
}
