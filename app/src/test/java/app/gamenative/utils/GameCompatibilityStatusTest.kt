package app.gamenative.utils

import app.gamenative.data.GameCompatibilityStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class GameCompatibilityStatusTest {
    @Test
    fun x86Host_ignoresPlayCountsFromOtherDevices() {
        val response = response(totalPlayableCount = 12, gpuPlayableCount = 0, isNotWorking = true)
        assertEquals(
            GameCompatibilityStatus.UNKNOWN,
            GameCompatibilityService.statusFor(response, HostCpu.X86_64),
        )
    }

    @Test
    fun x86Host_marksCompatibleWhenThisGpuHasPlayableRuns() {
        val response = response(totalPlayableCount = 4, gpuPlayableCount = 2, isNotWorking = true)
        assertEquals(
            GameCompatibilityStatus.GPU_COMPATIBLE,
            GameCompatibilityService.statusFor(response, HostCpu.X86_64),
        )
    }

    @Test
    fun armHost_keepsCrossDeviceCompatibleAndNotWorking() {
        assertEquals(
            GameCompatibilityStatus.COMPATIBLE,
            GameCompatibilityService.statusFor(
                response(totalPlayableCount = 3, gpuPlayableCount = 0),
                HostCpu.ARM64,
            ),
        )
        assertEquals(
            GameCompatibilityStatus.NOT_COMPATIBLE,
            GameCompatibilityService.statusFor(
                response(totalPlayableCount = 0, gpuPlayableCount = 0, isNotWorking = true),
                HostCpu.ARM64,
            ),
        )
    }

    private fun response(
        totalPlayableCount: Int,
        gpuPlayableCount: Int,
        isNotWorking: Boolean = false,
    ) = GameCompatibilityService.GameCompatibilityResponse(
        gameName = "Half-Life 2",
        totalPlayableCount = totalPlayableCount,
        gpuPlayableCount = gpuPlayableCount,
        avgRating = 0f,
        hasBeenTried = true,
        isNotWorking = isNotWorking,
    )
}
