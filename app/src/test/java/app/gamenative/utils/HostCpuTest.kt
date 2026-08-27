package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class HostCpuTest {

    @Test
    @Config(sdk = [33], qualifiers = "x86_64")
    fun current_returnsX86_64OnX86Host() {
        assertEquals(HostCpu.X86_64, HostCpu.current())
    }

    @Test
    @Config(sdk = [33], qualifiers = "arm64-v8a")
    fun current_returnsArm64OnArmHost() {
        assertEquals(HostCpu.ARM64, HostCpu.current())
    }
}
