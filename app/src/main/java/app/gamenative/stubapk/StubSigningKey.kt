package app.gamenative.stubapk

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.util.Calendar
import timber.log.Timber

/**
 * The key generated stub APKs are signed with.
 *
 * Kept in the platform keystore, where the private half cannot be read back out -- not by us and
 * not by anything that gets hold of the app's storage. A key shipped in the app would be a
 * private key published to everyone who has the ROM, and it would buy nothing: a stub holds no
 * permissions and shares no signature-level anything with GameNative.
 *
 * The keystore issues its own self-signed certificate for a generated key, which is exactly what
 * an APK signature needs to carry, so nothing here has to build one.
 *
 * Generated once and then reused, because a package can only be updated by the key that signed
 * it: a stub regenerated with a different key would fail to install over its predecessor.
 */
object StubSigningKey {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "gamenative-stub-signing"

    /** Long, because a stub is signed once and never re-signed. */
    private const val VALIDITY_YEARS = 30

    data class Key(val privateKey: PrivateKey, val certificate: ByteArray)

    /** The signing key, generating it on first use. */
    @Synchronized
    fun get(): Key {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(ALIAS)) {
            Timber.i("[StubSigningKey]: generating the stub signing key")
            generate()
        }

        val privateKey = keyStore.getKey(ALIAS, null) as PrivateKey
        val certificate = keyStore.getCertificate(ALIAS).encoded
        return Key(privateKey, certificate)
    }

    private fun generate() {
        val notBefore = Calendar.getInstance()
        val notAfter = (notBefore.clone() as Calendar).apply { add(Calendar.YEAR, VALIDITY_YEARS) }

        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setKeySize(2048)
            .setCertificateSubject(javax.security.auth.x500.X500Principal("CN=GameNative Stub"))
            .setCertificateNotBefore(notBefore.time)
            .setCertificateNotAfter(notAfter.time)
            .build()

        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE)
            .apply { initialize(spec) }
            .generateKeyPair()
    }
}
