package app.gamenative.stubapk

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural checks on the generated archive.
 *
 * Whether the platform accepts the chunks inside it is not something a unit test can answer;
 * that is verified by running aapt2 over the file this writes out, and ultimately by installing
 * it. Set stubapk.out to a path to keep a copy for that.
 */
class StubApkTest {

    private val dex = "dex\n035\u0000".toByteArray()
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

    private fun spec() = StubApk.Spec(
        packageName = "app.gamenative.stub.galculator",
        label = "Galculator",
        activityClass = "app.gamenative.stub.LaunchActivity",
        metadata = mapOf("app.gamenative.stub.ARGV" to "galculator"),
        iconPng = png,
        dex = dex,
    )

    @Test
    fun `writes a readable archive holding what a package needs`() {
        val apk = File.createTempFile("stub", ".apk").apply { deleteOnExit() }
        apk.writeBytes(StubApk.build(spec()))

        System.getProperty("stubapk.out")?.takeIf { it.isNotEmpty() }
            ?.let { apk.copyTo(File(it), overwrite = true) }

        ZipFile(apk).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertEquals(
                listOf("AndroidManifest.xml", "resources.arsc", "res/icon.png", "classes.dex"),
                names,
            )
            // Mapped in place by the platform, so it has to stay uncompressed.
            assertEquals(java.util.zip.ZipEntry.STORED.toLong(), zip.getEntry("resources.arsc").method.toLong())
            assertTrue(zip.getInputStream(zip.getEntry("classes.dex")).readBytes().contentEquals(dex))
        }
    }

    @Test
    fun `aligns the resource table`() {
        val bytes = StubApk.build(spec())
        // The local header is 30 bytes plus the name and the extra field, and the data follows.
        val header = String(bytes).indexOf("resources.arsc")
        val nameOffset = header
        val extraSize = (bytes[nameOffset - 2].toInt() and 0xff) or ((bytes[nameOffset - 1].toInt() and 0xff) shl 8)
        val dataOffset = nameOffset + "resources.arsc".length + extraSize
        assertEquals(0, dataOffset % 4)
    }

    @Test
    fun `refuses a package name that does not fit the table`() {
        val long = "a".repeat(128)
        val failure = runCatching { StubApk.build(spec().copy(packageName = long)) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }
}
