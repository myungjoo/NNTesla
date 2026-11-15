package io.github.myungjoo.nntesla.protocol

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The client's long-term NIST P-256 key. Implementations may keep the private key in hardware
 * (e.g. Android Keystore); the protocol only needs the public point and an ECDH operation.
 */
interface EcdhKey {
    /** Uncompressed public point: `0x04 || X(32) || Y(32)`. */
    val publicKeyBytes: ByteArray

    /** Returns the 32-byte big-endian X coordinate of the ECDH shared point. */
    fun sharedSecret(peer: ECPublicKey): ByteArray
}

/** Software [EcdhKey] backed by the default JCA provider. Used for tests and tooling. */
class JcaEcdhKey(
    private val privateKey: PrivateKey,
    override val publicKeyBytes: ByteArray,
    private val provider: String? = null,
) : EcdhKey {
    override fun sharedSecret(peer: ECPublicKey): ByteArray {
        val agreement = if (provider == null) {
            KeyAgreement.getInstance("ECDH")
        } else {
            KeyAgreement.getInstance("ECDH", provider)
        }
        agreement.init(privateKey)
        agreement.doPhase(peer, true)
        return agreement.generateSecret()
    }

    companion object {
        fun generate(random: SecureRandom = SecureRandom()): JcaEcdhKey {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"), random)
            val pair = generator.generateKeyPair()
            return JcaEcdhKey(pair.private, P256.encodePublic(pair.public as ECPublicKey))
        }

        /** Builds a key from a raw private scalar and its (externally known) public point. */
        fun fromScalar(scalar: ByteArray, publicKeyBytes: ByteArray): JcaEcdhKey {
            val spec = ECPrivateKeySpec(BigInteger(1, scalar), P256.params)
            val privateKey = KeyFactory.getInstance("EC").generatePrivate(spec)
            return JcaEcdhKey(privateKey, publicKeyBytes)
        }
    }
}

object P256 {
    val params: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
    }

    fun encodePublic(key: ECPublicKey): ByteArray =
        byteArrayOf(0x04) + fixed32(key.w.affineX) + fixed32(key.w.affineY)

    fun decodePublic(encoded: ByteArray): ECPublicKey {
        if (encoded.size != 65 || encoded[0] != 0x04.toByte()) {
            throw ProtocolException("invalid public key encoding")
        }
        val x = BigInteger(1, encoded.copyOfRange(1, 33))
        val y = BigInteger(1, encoded.copyOfRange(33, 65))
        val spec = ECPublicKeySpec(ECPoint(x, y), params)
        return try {
            KeyFactory.getInstance("EC").generatePublic(spec) as ECPublicKey
        } catch (e: Exception) {
            throw ProtocolException("invalid public key", e)
        }
    }

    private fun fixed32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }
}

/**
 * Symmetric state derived from ECDH: `K = SHA1(Sx)[:16]`, used for AES-128-GCM and as the root of
 * the HMAC-SHA256 sub-keys.
 */
class SessionKey(private val key: ByteArray) {
    init {
        require(key.size == KEY_SIZE)
    }

    private val secretKey = SecretKeySpec(key, "AES")
    private val random = SecureRandom()

    class Sealed(val nonce: ByteArray, val ciphertext: ByteArray, val tag: ByteArray)

    fun encrypt(plaintext: ByteArray, associatedData: ByteArray): Sealed {
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        return encrypt(plaintext, associatedData, nonce)
    }

    fun encrypt(plaintext: ByteArray, associatedData: ByteArray, nonce: ByteArray): Sealed {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_SIZE * 8, nonce))
        cipher.updateAAD(associatedData)
        val sealed = cipher.doFinal(plaintext)
        val split = sealed.size - TAG_SIZE
        return Sealed(nonce, sealed.copyOfRange(0, split), sealed.copyOfRange(split, sealed.size))
    }

    fun decrypt(nonce: ByteArray, ciphertext: ByteArray, tag: ByteArray, associatedData: ByteArray): ByteArray {
        if (nonce.size != NONCE_SIZE) throw ProtocolException("invalid AES-GCM nonce length")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_SIZE * 8, nonce))
        cipher.updateAAD(associatedData)
        return try {
            cipher.doFinal(ciphertext + tag)
        } catch (e: Exception) {
            throw ProtocolException("response authentication failed", e)
        }
    }

    /** HMAC-SHA256 context keyed with `HMAC-SHA256(K, label)`. */
    fun newHmac(label: String): Mac {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(subkey(label), "HmacSHA256"))
        return mac
    }

    fun subkey(label: String): ByteArray {
        val kdf = Mac.getInstance("HmacSHA256")
        kdf.init(SecretKeySpec(key, "HmacSHA256"))
        return kdf.doFinal(label.toByteArray(Charsets.US_ASCII))
    }

    companion object {
        const val KEY_SIZE = 16
        const val NONCE_SIZE = 12
        const val TAG_SIZE = 16

        fun derive(local: EcdhKey, peerPublic: ByteArray): SessionKey {
            val shared = local.sharedSecret(P256.decodePublic(peerPublic))
            val digest = MessageDigest.getInstance("SHA-1").digest(shared)
            return SessionKey(digest.copyOf(KEY_SIZE))
        }
    }
}

class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
