package app.gamenative.stubapk

import java.io.File
import java.security.KeyPairGenerator
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Checks the certificate and the signed archive as far as a unit test can.
 *
 * Whether the signature satisfies the platform is answered by apksigner over the file this
 * writes out, and finally by installing it; set stubapk.signed.out to keep a copy.
 */
class ApkSignerTest {

    companion object {
        private lateinit var keyPair: java.security.KeyPair
        private lateinit var certificate: ByteArray

        @BeforeClass
        @JvmStatic
        fun generateKey() {
            keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            certificate = SelfSignedCertificate.issue(keyPair, "GameNative Stub")
        }
    }

    private fun parsed(): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(certificate.inputStream()) as X509Certificate

    @Test
    fun `issues a certificate the platform's own parser accepts`() {
        val parsed = parsed()

        assertEquals("CN=GameNative Stub", parsed.subjectX500Principal.name)
        assertEquals(parsed.subjectX500Principal, parsed.issuerX500Principal)
        assertEquals(3, parsed.version)
        assertEquals(keyPair.public, parsed.publicKey)
        // Self-signed, so it verifies against the key it carries.
        parsed.verify(keyPair.public)
        parsed.checkValidity()
    }

    @Test
    fun `signs an archive that still reads as one`() {
        val unsigned = StubApk.build(
            StubApk.Spec(
                packageName = "app.gamenative.stub.example",
                label = "Example",
                activityClass = "app.gamenative.stub.LaunchActivity",
                metadata = mapOf("app.gamenative.stub.ARGV" to "example"),
                iconPng = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()),
                dex = "dex\n035\u0000".toByteArray(),
            ),
        )

        val signed = ApkSigner.sign(unsigned, keyPair.private, certificate)
        assertTrue("signing should add a block", signed.size > unsigned.size)

        val file = File.createTempFile("signed", ".apk").apply { deleteOnExit() }
        file.writeBytes(signed)
        System.getProperty("stubapk.signed.out")?.takeIf { it.isNotEmpty() }
            ?.let { file.copyTo(File(it), overwrite = true) }

        // The directory offset was rewritten correctly if the entries are still all findable.
        ZipFile(file).use { zip ->
            assertEquals(4, zip.size())
            assertTrue(zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes().isNotEmpty())
        }
    }
}
