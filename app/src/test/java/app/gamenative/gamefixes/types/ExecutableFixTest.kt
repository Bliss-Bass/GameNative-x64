package app.gamenative.gamefixes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.GameSource
import com.winlator.container.Container
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExecutableFixTest {
    private lateinit var context: Context
    private lateinit var container: Container
    private val fix = ExecutableFix(executable = "DOOMx64vk.exe", replaces = "DOOMx64.exe")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        container = mockk(relaxed = true)
        every { container.executablePath = any() } just runs
        every { container.saveData() } just runs
    }

    private fun apply() = fix.apply(context, "379720", "/tmp/game", "A:\\", container)

    @Test
    fun apply_setsExecutable_whenContainerHasNone() {
        every { container.executablePath } returns ""

        assertTrue(apply())
        verify(exactly = 1) { container.executablePath = "DOOMx64vk.exe" }
        verify(exactly = 1) { container.saveData() }
    }

    @Test
    fun apply_replacesTheDefaultLauncher_ignoringCase() {
        every { container.executablePath } returns "doomx64.EXE"

        assertTrue(apply())
        verify(exactly = 1) { container.executablePath = "DOOMx64vk.exe" }
    }

    @Test
    fun apply_keepsAnExecutableTheUserPicked() {
        every { container.executablePath } returns "tools\\idStudio.exe"

        assertTrue(apply())
        verify(exactly = 0) { container.executablePath = any() }
        verify(exactly = 0) { container.saveData() }
    }

    @Test
    fun doomFix_stillExposesItsLaunchArgConflict() {
        val composite = STEAM_Fix_379720 as KeyedCompositeGameFix
        val launchArgFix = composite.fixes.filterIsInstance<LaunchArgConflictFix>().single()
        every { container.execArgs } returns "+r_renderAPI 0"
        every { container.getExtra(any()) } returns ""

        assertSame(GameSource.STEAM, composite.gameSource)
        assertTrue(launchArgFix.findConflict(container) != null)
    }
}
