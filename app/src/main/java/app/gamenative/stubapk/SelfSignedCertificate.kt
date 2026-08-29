package app.gamenative.stubapk

import java.security.KeyPair
import java.security.SecureRandom
import java.security.Signature
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Issues the self-signed certificate a generated APK is signed with.
 *
 * The key is the device's own rather than one shipped with the app, so there is no private key
 * sitting in the ROM for anyone to extract and sign a package with. Nothing depends on the
 * certificate's identity: a stub holds no permissions and shares no signature-level anything,
 * and it is only ever installed by us.
 */
internal object SelfSignedCertificate {

    private const val OID_RSA_SHA256 = "1.2.840.113549.1.1.11"
    private const val OID_COMMON_NAME = "2.5.4.3"

    /** How long the certificate is valid for. Long, because a stub is never re-signed. */
    private const val VALIDITY_YEARS = 30

    /** A DER-encoded X.509 certificate over [keyPair]'s public key, signed with its private. */
    fun issue(keyPair: KeyPair, commonName: String): ByteArray {
        val algorithm = Der.sequence(Der.oid(OID_RSA_SHA256), Der.nullValue())
        val name = Der.sequence(
            Der.set(Der.sequence(Der.oid(OID_COMMON_NAME), Der.utf8String(commonName))),
        )

        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val notBefore = time(calendar)
        calendar.add(Calendar.YEAR, VALIDITY_YEARS)
        val notAfter = time(calendar)

        val tbs = Der.sequence(
            Der.tagged(0, Der.integer(2)), // v3
            Der.positiveInteger(ByteArray(8).also { SecureRandom().nextBytes(it) }),
            algorithm,
            name, // issuer, the same as the subject when self-signed
            Der.sequence(notBefore, notAfter),
            name,
            // Already a SubjectPublicKeyInfo, which is exactly what belongs here.
            Der.raw(keyPair.public.encoded),
        )

        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(tbs)
            sign()
        }

        return Der.sequence(tbs, algorithm, Der.bitString(signature))
    }

    /**
     * A validity bound in whichever of the two time types X.509 calls for: UTCTime carries two
     * digits of year and is defined to run out at the end of 2049, so a certificate valid past
     * then has to say so in GeneralizedTime or be read as expiring in the 1950s.
     */
    private fun time(calendar: Calendar): ByteArray {
        val generalized = calendar.get(Calendar.YEAR) >= 2050
        val pattern = if (generalized) "yyyyMMddHHmmss'Z'" else "yyMMddHHmmss'Z'"
        val text = SimpleDateFormat(pattern, Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(calendar.time)
        return if (generalized) Der.generalizedTime(text) else Der.utcTime(text)
    }
}
