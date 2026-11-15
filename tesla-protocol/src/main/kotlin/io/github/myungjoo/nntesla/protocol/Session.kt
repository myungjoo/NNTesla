package io.github.myungjoo.nntesla.protocol

import com.google.protobuf.ByteString
import com.tesla.generated.signatures.Signatures
import com.tesla.generated.signatures.Signatures.SessionInfo
import com.tesla.generated.signatures.Signatures.SignatureType
import com.tesla.generated.signatures.Signatures.Tag
import com.tesla.generated.universalmessage.UniversalMessage.Destination
import com.tesla.generated.universalmessage.UniversalMessage.Domain
import com.tesla.generated.universalmessage.UniversalMessage.RoutableMessage
import java.security.MessageDigest

/**
 * Authenticated session with one vehicle domain (the "Signer" side of the protocol).
 *
 * Holds the shared AES key, the domain's epoch/clock and the anti-replay counter. All methods are
 * thread-safe so that pipelined requests can be authorized from any thread.
 */
class Session private constructor(
    val domain: Domain,
    private val vin: ByteArray,
    private val localKey: EcdhKey,
    private val key: SessionKey,
    private val verifierPublic: ByteArray,
    private var counter: Long,
    private var epoch: ByteArray,
    private var timeZeroNanos: Long,
    private var setTime: Long,
    private val nanoClock: () -> Long,
) {
    /** Result of authorizing a request: the wire message and the hash needed to decrypt its reply. */
    class Authorized(val message: RoutableMessage, val requestHash: ByteArray)

    /**
     * Encrypts [payload] (an encoded `CarServer.Action` or `VCSEC.UnsignedMessage`) with
     * AES-GCM-Personalized and wraps it in a [RoutableMessage].
     */
    @Synchronized
    fun authorize(
        payload: ByteArray,
        routingAddress: ByteArray,
        uuid: ByteArray,
        flags: Int = FLAG_ENCRYPT_RESPONSE,
        ttlMillis: Long = DEFAULT_TTL_MILLIS,
    ): Authorized {
        if (counter >= 0xFFFF_FFFFL) throw ProtocolException("counter rollover")
        counter += 1
        val expiresAt = (nanoClock() + ttlMillis * 1_000_000L - timeZeroNanos) / 1_000_000_000L

        val meta = Metadata.sha256()
            .add(Tag.TAG_SIGNATURE_TYPE, byteArrayOf(SignatureType.SIGNATURE_TYPE_AES_GCM_PERSONALIZED_VALUE.toByte()))
            .add(Tag.TAG_DOMAIN, byteArrayOf(domain.number.toByte()))
            .add(Tag.TAG_PERSONALIZATION, vin)
            .add(Tag.TAG_EPOCH, epoch)
            .addUInt32(Tag.TAG_EXPIRES_AT, expiresAt)
            .addUInt32(Tag.TAG_COUNTER, counter)
        if (flags != 0) meta.addUInt32(Tag.TAG_FLAGS, flags.toLong())
        val sealed = key.encrypt(payload, meta.checksum())

        val gcmData = Signatures.AES_GCM_Personalized_Signature_Data.newBuilder()
            .setEpoch(ByteString.copyFrom(epoch))
            .setNonce(ByteString.copyFrom(sealed.nonce))
            .setCounter(counter.toInt())
            .setExpiresAt(expiresAt.toInt())
            .setTag(ByteString.copyFrom(sealed.tag))
        val signatureData = Signatures.SignatureData.newBuilder()
            .setSignerIdentity(
                Signatures.KeyIdentity.newBuilder().setPublicKey(ByteString.copyFrom(localKey.publicKeyBytes)),
            )
            .setAESGCMPersonalizedData(gcmData)
        val message = RoutableMessage.newBuilder()
            .setToDestination(Destination.newBuilder().setDomain(domain))
            .setFromDestination(Destination.newBuilder().setRoutingAddress(ByteString.copyFrom(routingAddress)))
            .setProtobufMessageAsBytes(ByteString.copyFrom(sealed.ciphertext))
            .setSignatureData(signatureData)
            .setUuid(ByteString.copyFrom(uuid))
            .setFlags(flags)
            .build()
        val requestHash = byteArrayOf(SignatureType.SIGNATURE_TYPE_AES_GCM_PERSONALIZED_VALUE.toByte()) + sealed.tag
        return Authorized(message, requestHash)
    }

    /**
     * Decrypts a response in place. Returns the plaintext payload and the response counter, which
     * the caller must check for replays. Plaintext responses (no AES-GCM response data) are
     * returned as-is with a null counter.
     */
    fun decryptResponse(response: RoutableMessage, requestHash: ByteArray): Decrypted {
        val sigData = response.signatureData
        if (!sigData.hasAESGCMResponseData()) {
            return Decrypted(response.protobufMessageAsBytes.toByteArray(), null)
        }
        val gcm = sigData.aesgcmResponseData
        val counter = gcm.counter.toLong() and 0xFFFF_FFFFL
        val aad = Metadata.sha256()
            .add(Tag.TAG_SIGNATURE_TYPE, byteArrayOf(SignatureType.SIGNATURE_TYPE_AES_GCM_RESPONSE_VALUE.toByte()))
            .add(Tag.TAG_DOMAIN, byteArrayOf(response.fromDestination.domain.number.toByte()))
            .add(Tag.TAG_PERSONALIZATION, vin)
            .addUInt32(Tag.TAG_COUNTER, counter)
            .addUInt32(Tag.TAG_FLAGS, response.flags.toLong() and 0xFFFF_FFFFL)
            .add(Tag.TAG_REQUEST_HASH, requestHash)
            .addUInt32(Tag.TAG_FAULT, response.signedMessageStatus.signedMessageFault.number.toLong())
            .checksum()
        val plaintext = key.decrypt(
            gcm.nonce.toByteArray(),
            response.protobufMessageAsBytes.toByteArray(),
            gcm.tag.toByteArray(),
            aad,
        )
        return Decrypted(plaintext, counter)
    }

    class Decrypted(val payload: ByteArray, val counter: Long?)

    /**
     * Re-synchronizes with session info the vehicle attached to an error reply. [challenge] must
     * be the UUID of a request this client sent recently.
     */
    @Synchronized
    fun updateSignedSessionInfo(challenge: ByteArray, encodedInfo: ByteArray, tag: ByteArray) {
        verifySessionInfoTag(key, vin, challenge, encodedInfo, tag)
        val info = SessionInfo.parseFrom(encodedInfo)
        if (!MessageDigest.isEqual(info.publicKey.toByteArray(), verifierPublic)) {
            throw ProtocolException("session info public key does not match the session")
        }
        val clockTime = info.clockTime.toLong() and 0xFFFF_FFFFL
        val newEpoch = info.epoch.toByteArray()
        if (!newEpoch.contentEquals(epoch) || setTime <= clockTime) {
            val newCounter = info.counter.toLong() and 0xFFFF_FFFFL
            // Never roll the counter back (mirrors the upstream Go signer).
            if (counter < newCounter) counter = newCounter
            epoch = newEpoch
            setTime = clockTime
            timeZeroNanos = nanoClock() - clockTime * 1_000_000_000L
        }
    }

    companion object {
        const val FLAG_ENCRYPT_RESPONSE = 1 shl 1 // 1 << UniversalMessage.FLAG_ENCRYPT_RESPONSE
        const val DEFAULT_TTL_MILLIS = 5_000L

        /**
         * Completes the handshake: verifies the vehicle's session info reply to a
         * `SessionInfoRequest` whose UUID was [challenge], and derives the shared key.
         */
        fun fromHandshake(
            domain: Domain,
            vin: String,
            localKey: EcdhKey,
            challenge: ByteArray,
            encodedInfo: ByteArray,
            tag: ByteArray,
            nanoClock: () -> Long = System::nanoTime,
        ): Session {
            val info = SessionInfo.parseFrom(encodedInfo)
            if (info.status == Signatures.Session_Info_Status.SESSION_INFO_STATUS_KEY_NOT_ON_WHITELIST) {
                throw KeyNotPairedException()
            }
            val verifierPublic = info.publicKey.toByteArray()
            val key = SessionKey.derive(localKey, verifierPublic)
            val vinBytes = vin.toByteArray(Charsets.US_ASCII)
            verifySessionInfoTag(key, vinBytes, challenge, encodedInfo, tag)
            val clockTime = info.clockTime.toLong() and 0xFFFF_FFFFL
            return Session(
                domain = domain,
                vin = vinBytes,
                localKey = localKey,
                key = key,
                verifierPublic = verifierPublic,
                counter = info.counter.toLong() and 0xFFFF_FFFFL,
                epoch = info.epoch.toByteArray(),
                timeZeroNanos = nanoClock() - clockTime * 1_000_000_000L,
                setTime = clockTime,
                nanoClock = nanoClock,
            )
        }

        internal fun sessionInfoTag(key: SessionKey, vin: ByteArray, challenge: ByteArray, encodedInfo: ByteArray): ByteArray =
            Metadata.hmac(key.newHmac("session info"))
                .add(Tag.TAG_SIGNATURE_TYPE, byteArrayOf(SignatureType.SIGNATURE_TYPE_HMAC_VALUE.toByte()))
                .add(Tag.TAG_PERSONALIZATION, vin)
                .add(Tag.TAG_CHALLENGE, challenge)
                .checksum(encodedInfo)

        private fun verifySessionInfoTag(
            key: SessionKey,
            vin: ByteArray,
            challenge: ByteArray,
            encodedInfo: ByteArray,
            tag: ByteArray,
        ) {
            val expected = sessionInfoTag(key, vin, challenge, encodedInfo)
            if (!MessageDigest.isEqual(expected, tag)) {
                throw ProtocolException("session info HMAC invalid")
            }
        }
    }
}

/** The vehicle does not know our public key; it must be paired first (keycard tap). */
class KeyNotPairedException : Exception("key is not paired with the vehicle")
