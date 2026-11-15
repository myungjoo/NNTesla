package io.github.myungjoo.nntesla.protocol

import com.tesla.generated.signatures.Signatures.Tag
import java.security.MessageDigest
import javax.crypto.Mac

/**
 * Tag-length-value metadata serializer used to bind authenticated data (VIN, epoch, counter, ...)
 * to protocol messages. See "Metadata serialization" in upstream protocol.md.
 *
 * Items must be added in strictly ascending tag order. The serialized form is fed into a hash
 * ([sha256]) or an HMAC ([hmac]) context and terminated with [Tag.TAG_END] by [checksum].
 */
internal class Metadata private constructor(
    private val update: (ByteArray) -> Unit,
    private val finish: () -> ByteArray,
) {
    private var last = -1

    fun add(tag: Tag, value: ByteArray): Metadata {
        val number = tag.number
        require(number > last) { "metadata items must be added in increasing tag order" }
        require(value.size <= 255) { "metadata fields can't be more than 255 bytes long" }
        last = number
        update(byteArrayOf(number.toByte(), value.size.toByte()))
        update(value)
        return this
    }

    fun addUInt32(tag: Tag, value: Long): Metadata = add(tag, uint32BigEndian(value))

    /** Terminates the metadata, appends [message] and returns the digest / MAC. */
    fun checksum(message: ByteArray = EMPTY): ByteArray {
        update(byteArrayOf(Tag.TAG_END.number.toByte()))
        update(message)
        return finish()
    }

    companion object {
        private val EMPTY = ByteArray(0)

        fun sha256(): Metadata {
            val digest = MessageDigest.getInstance("SHA-256")
            return Metadata({ digest.update(it) }, { digest.digest() })
        }

        fun hmac(mac: Mac): Metadata = Metadata({ mac.update(it) }, { mac.doFinal() })

        fun uint32BigEndian(value: Long): ByteArray {
            require(value in 0..0xFFFF_FFFFL) { "value out of uint32 range: $value" }
            return byteArrayOf(
                (value ushr 24).toByte(),
                (value ushr 16).toByte(),
                (value ushr 8).toByte(),
                value.toByte(),
            )
        }
    }
}
