package io.github.myungjoo.nntesla.protocol

import java.security.MessageDigest

/**
 * BLE link-layer framing: every message is prefixed with its 2-byte big-endian length and may be
 * split across several GATT writes / indications.
 */
object BleFraming {
    const val SERVICE_UUID = "00000211-b2d1-43f0-9b88-960cebf8b91e"
    const val TO_VEHICLE_UUID = "00000212-b2d1-43f0-9b88-960cebf8b91e"
    const val FROM_VEHICLE_UUID = "00000213-b2d1-43f0-9b88-960cebf8b91e"

    /** Upper bound for an inbound message; larger length prefixes are treated as corruption. */
    const val MAX_MESSAGE_SIZE = 4096

    /** Splits [message] into length-prefixed chunks of at most [blockLength] bytes. */
    fun encode(message: ByteArray, blockLength: Int): List<ByteArray> {
        require(message.size <= 0xFFFF) { "message too large" }
        require(blockLength > 0)
        val framed = byteArrayOf((message.size ushr 8).toByte(), message.size.toByte()) + message
        return (framed.indices step blockLength).map { start ->
            framed.copyOfRange(start, minOf(start + blockLength, framed.size))
        }
    }

    /** Advertised local name: `S` + hex(SHA1(VIN)[:8]) + `C`. */
    fun localName(vin: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(vin.toByteArray(Charsets.US_ASCII))
        return "S" + digest.copyOf(8).joinToString("") { "%02x".format(it) } + "C"
    }
}

/**
 * Reassembles inbound chunks into complete messages. Not thread-safe: feed it from the single
 * GATT callback thread.
 */
class BleReassembler(
    private val rxTimeoutNanos: Long = 1_000_000_000L,
    private val nanoClock: () -> Long = System::nanoTime,
) {
    private var buffer = ByteArray(0)
    private var lastRx = Long.MIN_VALUE

    /** True when no partial message is buffered (the next chunk starts a new message). */
    val isIdle: Boolean get() = buffer.isEmpty()

    /** Appends [chunk] and returns any messages it completes (usually zero or one). */
    fun feed(chunk: ByteArray): List<ByteArray> {
        val now = nanoClock()
        if (lastRx != Long.MIN_VALUE && now - lastRx > rxTimeoutNanos) {
            buffer = ByteArray(0)
        }
        lastRx = now
        buffer += chunk
        val out = ArrayList<ByteArray>(1)
        while (buffer.size >= 2) {
            val length = ((buffer[0].toInt() and 0xFF) shl 8) or (buffer[1].toInt() and 0xFF)
            if (length > BleFraming.MAX_MESSAGE_SIZE) {
                buffer = ByteArray(0)
                break
            }
            if (buffer.size < 2 + length) break
            out += buffer.copyOfRange(2, 2 + length)
            buffer = buffer.copyOfRange(2 + length, buffer.size)
        }
        return out
    }
}
