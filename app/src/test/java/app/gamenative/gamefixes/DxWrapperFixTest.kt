package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.R
import com.winlator.container.Container
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DxWrapperFixTest {
    private val context = mockk<Context>(relaxed = true)
    private val fix = DxWrapperFix("vkd3d")

    @Test
    fun applyLeavesTheWrapperForThePrompt() {
        val container = mockk<Container>(relaxed = true)
        every { container.dxWrapper } returns "dxvk"

        assertTrue(fix.apply(context, "4c2659cf3411441dbc02ffc965643e47", "/games", "A:\\", container))

        verify(exactly = 0) { container.saveData() }
    }

    @Test
    fun findConflictOffersVkd3dWhileStillOnDxvk() {
        val container = mockk<Container>(relaxed = true)
        every { container.dxWrapper } returns "dxvk"
        every { container.getExtra(any()) } returns ""

        assertEquals(
            LaunchArgConflict(
                found = "DXVK",
                replacement = "VKD3D",
                titleRes = R.string.dx_wrapper_conflict_title,
                messageRes = R.string.dx_wrapper_conflict_message,
            ),
            fix.findConflict(container),
        )
    }

    @Test
    fun findConflictStaysQuietForAnotherWrapperOrAKeptChoice() {
        val other = mockk<Container>(relaxed = true)
        every { other.dxWrapper } returns "wined3d"
        assertNull(fix.findConflict(other))

        val kept = mockk<Container>(relaxed = true)
        every { kept.dxWrapper } returns "dxvk"
        every { kept.getExtra(any()) } returns "dxvk"
        assertNull(fix.findConflict(kept))
    }

    @Test
    fun resolveConflictSwitchesOrRemembers() {
        val accepted = mockk<Container>(relaxed = true)
        fix.resolveConflict(accepted, LaunchArgConflict("DXVK", "VKD3D"), accept = true)
        verify { accepted.dxWrapper = "vkd3d" }
        verify(exactly = 1) { accepted.saveData() }

        val kept = mockk<Container>(relaxed = true)
        every { kept.dxWrapper } returns "dxvk"
        fix.resolveConflict(kept, LaunchArgConflict("DXVK", "VKD3D"), accept = false)
        verify { kept.putExtra("gameFixKeptDxWrapper", "dxvk") }
        verify(exactly = 0) { kept.dxWrapper = any() }
    }
}
