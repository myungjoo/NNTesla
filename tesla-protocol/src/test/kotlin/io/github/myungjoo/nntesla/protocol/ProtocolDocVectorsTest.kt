package io.github.myungjoo.nntesla.protocol

import com.google.protobuf.ByteString
import com.tesla.generated.signatures.Signatures
import com.tesla.generated.signatures.Signatures.SignatureType
import com.tesla.generated.signatures.Signatures.Tag
import com.tesla.generated.universalmessage.UniversalMessage.Destination
import com.tesla.generated.universalmessage.UniversalMessage.Domain
import com.tesla.generated.universalmessage.UniversalMessage.RoutableMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest

/**
 * Test vectors from upstream vehicle-command `pkg/protocol/protocol.md` (commit 0a582da).
 * These keys are published test keys; never enroll them on a real vehicle.
 */
class ProtocolDocVectorsTest {
    private val vin = "5YJ30123456789ABC"
    private val clientScalar = hex("2538CDC29A97C19C1E99A637D6CF4F8C970C118B56EDE1E6323E6D162C4B30DB")
    private val clientPublic = hex(
        "04b2b6bc68c2da0665ce656815594996c62394edd8bea905fe781a754fe6a845a7" +
            "14330902f225e9269d466e05b349981fda9d85cc23c6fb444aa73b629105dc6e",
    )
    private val vehiclePublic = hex(
        "04c7a1f47138486aa4729971494878d33b1a24e39571f748a6e16c5955b3d877d3" +
            "a6aaa0e955166474af5d32c410f439a2234137ad1bb085fd4e8813c958f11d97",
    )
    private val handshakeUuid = hex("1588d5a30eabc6f8fc9a951b11f6fd11")
    private val sessionInfo = hex(
        "0806124104c7a1f47138486aa4729971494878d33b1a24e39571f748a6e16c5955b3d877d3" +
            "a6aaa0e955166474af5d32c410f439a2234137ad1bb085fd4e8813c958f11d971a10" +
            "4c463f9cc0d3d26906e982ed224adde6255a0a0000",
    )
    private val sessionInfoTag = hex("996c1fe38331be138f8039c194b14db2198846ed7d8251e6749284d7b32ea002")
    private val sharedKey = hex("1b2fce19967b79db696f909cff89ea9a")

    private val clientKey = JcaEcdhKey.fromScalar(clientScalar, clientPublic)

    @Test
    fun keyAgreementMatchesDoc() {
        val key = SessionKey.derive(clientKey, vehiclePublic)
        assertArrayEquals(hex("fceb679ee7bca756fcd441bf238bf2f338629b41d9eb9c67be1b32c9672ce300"), key.subkey("session info"))
    }

    @Test
    fun sessionInfoTagMatchesDoc() {
        val tag = Session.sessionInfoTag(SessionKey(sharedKey), vin.toByteArray(), handshakeUuid, sessionInfo)
        assertArrayEquals(sessionInfoTag, tag)
    }

    @Test
    fun handshakeAcceptsDocResponse() {
        val session = Session.fromHandshake(Domain.DOMAIN_INFOTAINMENT, vin, clientKey, handshakeUuid, sessionInfo, sessionInfoTag)
        assertEquals(Domain.DOMAIN_INFOTAINMENT, session.domain)
    }

    @Test
    fun handshakeRejectsTamperedTag() {
        val bad = sessionInfoTag.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(ProtocolException::class.java) {
            Session.fromHandshake(Domain.DOMAIN_INFOTAINMENT, vin, clientKey, handshakeUuid, sessionInfo, bad)
        }
    }

    @Test
    fun requestMetadataAndCiphertextMatchDoc() {
        val expectedMetadata = hex(
            "000105010103021135594a333031323334353637383941424303104c463f9cc0d3d26906e982ed224adde6" +
                "040400000a5f050400000007070400000002ff",
        )
        val aad = Metadata.sha256()
            .add(Tag.TAG_SIGNATURE_TYPE, byteArrayOf(SignatureType.SIGNATURE_TYPE_AES_GCM_PERSONALIZED_VALUE.toByte()))
            .add(Tag.TAG_DOMAIN, byteArrayOf(Domain.DOMAIN_INFOTAINMENT_VALUE.toByte()))
            .add(Tag.TAG_PERSONALIZATION, vin.toByteArray())
            .add(Tag.TAG_EPOCH, hex("4c463f9cc0d3d26906e982ed224adde6"))
            .addUInt32(Tag.TAG_EXPIRES_AT, 2655)
            .addUInt32(Tag.TAG_COUNTER, 7)
            .addUInt32(Tag.TAG_FLAGS, Session.FLAG_ENCRYPT_RESPONSE.toLong())
            .checksum()
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(expectedMetadata), aad)

        val sealed = SessionKey(sharedKey).encrypt(hex("120452020801"), aad, hex("dbf79447fa156674dae1caed"))
        assertArrayEquals(hex("38038e8c0f2e"), sealed.ciphertext)
        assertArrayEquals(hex("c228e0ff64991481db3a7bbc133696c5"), sealed.tag)
    }

    @Test
    fun authorizedRequestUsesSessionStateAndDocAad() {
        // Session clock: handshake at t=0 with clock_time 2650; expires_at = 2650 + 5 s TTL.
        val session = Session.fromHandshake(
            Domain.DOMAIN_INFOTAINMENT, vin, clientKey, handshakeUuid, sessionInfo, sessionInfoTag,
            nanoClock = { 0L },
        )
        val payload = hex("120452020801")
        val authorized = session.authorize(payload, ByteArray(16), ByteArray(16))
        val gcm = authorized.message.signatureData.aesgcmPersonalizedData
        assertEquals(7, gcm.counter) // session_info.counter (6) + 1
        assertEquals(2655, gcm.expiresAt)

        // The vehicle side decrypts with the same AAD the doc example uses.
        val aad = MessageDigest.getInstance("SHA-256").digest(
            hex(
                "000105010103021135594a333031323334353637383941424303104c463f9cc0d3d26906e982ed224adde6" +
                    "040400000a5f050400000007070400000002ff",
            ),
        )
        val plaintext = SessionKey(sharedKey).decrypt(
            gcm.nonce.toByteArray(),
            authorized.message.protobufMessageAsBytes.toByteArray(),
            gcm.tag.toByteArray(),
            aad,
        )
        assertArrayEquals(payload, plaintext)
        assertArrayEquals(byteArrayOf(5) + gcm.tag.toByteArray(), authorized.requestHash)
    }

    @Test
    fun responseDecryptionRoundTrip() {
        val session = Session.fromHandshake(Domain.DOMAIN_INFOTAINMENT, vin, clientKey, handshakeUuid, sessionInfo, sessionInfoTag)
        val requestHash = byteArrayOf(5) + ByteArray(16) { it.toByte() }
        val payload = hex("0a00")
        // Encrypt as the vehicle would (response metadata, see "Response metadata" in protocol.md).
        val aad = Metadata.sha256()
            .add(Tag.TAG_SIGNATURE_TYPE, byteArrayOf(SignatureType.SIGNATURE_TYPE_AES_GCM_RESPONSE_VALUE.toByte()))
            .add(Tag.TAG_DOMAIN, byteArrayOf(Domain.DOMAIN_INFOTAINMENT_VALUE.toByte()))
            .add(Tag.TAG_PERSONALIZATION, vin.toByteArray())
            .addUInt32(Tag.TAG_COUNTER, 42)
            .addUInt32(Tag.TAG_FLAGS, 0)
            .add(Tag.TAG_REQUEST_HASH, requestHash)
            .addUInt32(Tag.TAG_FAULT, 0)
            .checksum()
        val sealed = SessionKey(sharedKey).encrypt(payload, aad)
        val response = RoutableMessage.newBuilder()
            .setFromDestination(Destination.newBuilder().setDomain(Domain.DOMAIN_INFOTAINMENT))
            .setProtobufMessageAsBytes(ByteString.copyFrom(sealed.ciphertext))
            .setSignatureData(
                Signatures.SignatureData.newBuilder().setAESGCMResponseData(
                    Signatures.AES_GCM_Response_Signature_Data.newBuilder()
                        .setNonce(ByteString.copyFrom(sealed.nonce))
                        .setCounter(42)
                        .setTag(ByteString.copyFrom(sealed.tag)),
                ),
            )
            .build()
        val decrypted = session.decryptResponse(response, requestHash)
        assertArrayEquals(payload, decrypted.payload)
        assertEquals(42L, decrypted.counter)

        val wrongHash = requestHash.copyOf().also { it[1] = 99 }
        assertThrows(ProtocolException::class.java) { session.decryptResponse(response, wrongHash) }
    }

    @Test
    fun bleLocalNameMatchesDoc() {
        assertEquals("S1a87a5a75f3df858C", BleFraming.localName("5YJS0000000000000"))
    }

    @Test
    fun bleFramingRoundTrip() {
        val message = ByteArray(700) { it.toByte() }
        val chunks = BleFraming.encode(message, 182)
        assertEquals(4, chunks.size)
        val reassembler = BleReassembler()
        val out = chunks.flatMap { reassembler.feed(it) }
        assertEquals(1, out.size)
        assertArrayEquals(message, out[0])
    }

    @Test
    fun bleReassemblerDropsStalePartialMessage() {
        var now = 0L
        val reassembler = BleReassembler(nanoClock = { now })
        val chunks = BleFraming.encode(ByteArray(10) { 1 }, 5)
        reassembler.feed(chunks[0])
        now += 2_000_000_000L
        assertEquals(0, reassembler.feed(byteArrayOf(0x00)).size) // stale prefix discarded
        val fresh = BleFraming.encode(byteArrayOf(9, 9), 100)
        now += 2_000_000_000L
        assertArrayEquals(byteArrayOf(9, 9), reassembler.feed(fresh[0]).single())
    }

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
