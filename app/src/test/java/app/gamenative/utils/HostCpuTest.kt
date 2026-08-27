package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HostCpuTest {

    @Test
    fun current_returnsX86_64OnX86Host() {
        ShadowBuild.setSupportedAbis(arrayOf("x86_64", "arm64-v8a", "x86"))
        assertEquals(HostCpu.X86_64, HostCpu.current())
    }

    @Test
    fun current_returnsArm64OnArmHost() {
        ShadowBuild.setSupportedAbis(arrayOf("arm64-v8a", "armeabi-v7a", "armeabi"))
        assertEquals(HostCpu.ARM64, HostCpu.current())
    }
}
