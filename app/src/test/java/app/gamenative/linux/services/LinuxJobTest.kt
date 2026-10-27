package app.gamenative.linux.services

import app.gamenative.linux.LinuxDistroCatalog
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LinuxJobTest {

    private fun job(extra: String = "") = JSONObject("""{"id": "web", "argv": ["python3", "-m", "http.server"] $extra}""")

    @Test
    fun aJobWithoutADistroRunsInUbuntuWhereJobsAlwaysRan() {
        assertEquals(LinuxDistroCatalog.UBUNTU, LinuxJob.parse(job()).distro)
    }

    @Test
    fun aJobCanRunInDebianOrArch() {
        assertEquals("debian", LinuxJob.parse(job(""", "distro": "debian"""")).distro)
        assertEquals("archlinux", LinuxJob.parse(job(""", "distro": "archlinux"""")).distro)
    }

    @Test
    fun experimentalAndUnknownDistrosAreRefused() {
        val experimental = assertThrows(IllegalArgumentException::class.java) {
            LinuxJob.parse(job(""", "distro": "alpine""""))
        }
        assertTrue(experimental.message!!.contains("experimental"))
        assertThrows(IllegalArgumentException::class.java) { LinuxJob.parse(job(""", "distro": "gentoo"""")) }
    }
}
