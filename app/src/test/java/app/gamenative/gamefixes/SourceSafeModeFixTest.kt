package app.gamenative.gamefixes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.winlator.container.Container
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
class SourceSafeModeFixTest {
    private lateinit var context: Context
    private lateinit var container: Container

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        container = mockk(relaxed = true)
    }

    @Test
    fun apply_dropsSafeAndAddsFullscreen_forASourceInstall() {
        val installDir = sourceInstall()
        every { container.execArgs } returns
            "-steam -game A:\\bms -console -novid -safe"
        every { container.execArgs = any() } just runs
        every { container.saveData() } just runs

        val result = SourceSafeModeFix.apply(context, "362890", installDir.absolutePath, "A:\\", container)

        assertTrue(result)
        verify {
            container.execArgs = "-steam -game A:\\bms -console -novid -fullscreen"
        }
        verify(exactly = 1) { container.saveData() }
    }

    @Test
    fun apply_leavesAnExplicitWindow_andDoesNotAddFullscreen() {
        val installDir = sourceInstall()
        every { container.execArgs } returns "-safe -windowed"
        every { container.execArgs = any() } just runs
        every { container.saveData() } just runs

        SourceSafeModeFix.apply(context, "362890", installDir.absolutePath, "A:\\", container)

        verify { container.execArgs = "-windowed" }
    }

    @Test
    fun apply_doesNothing_whenSafeIsAbsent() {
        val installDir = sourceInstall()
        every { container.execArgs } returns "-fullscreen -novid"

        val result = SourceSafeModeFix.apply(context, "362890", installDir.absolutePath, "A:\\", container)

        assertTrue(result)
        verify(exactly = 0) { container.saveData() }
    }

    @Test
    fun apply_doesNothing_whenTheInstallIsNotSource() {
        val installDir = createTempDirectory(prefix = "not-source").toFile()
        every { container.execArgs } returns "-safe"

        val result = SourceSafeModeFix.apply(context, "1", installDir.absolutePath, "A:\\", container)

        assertTrue(result)
        verify(exactly = 0) { container.saveData() }
    }

    @Test
    fun isSourceInstall_rejectsALookalikeFlagInsideAnotherToken() {
        val installDir = sourceInstall()
        every { container.execArgs } returns "-safemode"

        assertTrue(SourceSafeModeFix.isSourceInstall(installDir.absolutePath))
        assertTrue(SourceSafeModeFix.apply(context, "362890", installDir.absolutePath, "A:\\", container))
        verify(exactly = 0) { container.saveData() }
    }

    private fun sourceInstall(): File {
        val root = createTempDirectory(prefix = "black-mesa").toFile()
        val mod = File(root, "bms")
        mod.mkdirs()
        File(mod, "gameinfo.txt").writeText("\"GameInfo\" {}")
        return root
    }
}
