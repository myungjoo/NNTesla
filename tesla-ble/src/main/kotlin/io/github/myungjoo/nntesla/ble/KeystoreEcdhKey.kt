package io.github.myungjoo.nntesla.ble

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.myungjoo.nntesla.protocol.EcdhKey
import io.github.myungjoo.nntesla.protocol.P256
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement

/**
 * The app's vehicle key: a P-256 key pair kept in Android Keystore. The private key never leaves
 * the Keystore; ECDH runs inside it. This key can authenticate to the vehicle, so it must never
 * be exported.
 */
class KeystoreEcdhKey private constructor(
    private val privateKey: PrivateKey,
    override val publicKeyBytes: ByteArray,
) : EcdhKey {

    override fun sharedSecret(peer: ECPublicKey): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH", PROVIDER)
        agreement.init(privateKey)
        agreement.doPhase(peer, true)
        return agreement.generateSecret()
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "nntesla-vehicle-key"

        /** Loads the key under [alias], generating it on first use. */
        fun loadOrCreate(alias: String = DEFAULT_ALIAS): KeystoreEcdhKey {
            val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
            if (!store.containsAlias(alias)) {
                val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
                generator.initialize(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_AGREE_KEY)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .build(),
                )
                generator.generateKeyPair()
            }
            val entry = store.getEntry(alias, null) as KeyStore.PrivateKeyEntry
            val publicKey = entry.certificate.publicKey as ECPublicKey
            return KeystoreEcdhKey(entry.privateKey, P256.encodePublic(publicKey))
        }

        fun delete(alias: String = DEFAULT_ALIAS) {
            KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(alias)
        }
    }
}
