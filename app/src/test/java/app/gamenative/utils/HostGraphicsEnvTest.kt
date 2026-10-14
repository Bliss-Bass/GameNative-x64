package app.gamenative.utils

import com.winlator.core.envvars.EnvVars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostGraphicsEnvTest {
    @Test
    fun ensureDxvkDllOverrides_addsWhenMissing() {
        val env = EnvVars()
        HostGraphicsEnv.ensureDxvkDllOverrides(env)
        assertTrue(env.get("WINEDLLOVERRIDES").contains("d3d9=n,b"))
    }

    @Test
    fun ensureDxvkDllOverrides_preservesExisting() {
        val env = EnvVars("WINEDLLOVERRIDES=icu=n")
        HostGraphicsEnv.ensureDxvkDllOverrides(env)
        assertTrue(env.get("WINEDLLOVERRIDES").contains("icu=n"))
        assertTrue(env.get("WINEDLLOVERRIDES").contains("d3d9=n,b"))
    }

    @Test
    fun ensureDxvkDllOverrides_skipsWhenD3d9Present() {
        val env = EnvVars("WINEDLLOVERRIDES=d3d9=n,b")
        HostGraphicsEnv.ensureDxvkDllOverrides(env)
        assertFalse(env.get("WINEDLLOVERRIDES").contains(";"))
    }

    @Test
    fun pinIntelDxgiVendor_setsVendorOnIntel() {
        val env = EnvVars()
        HostGraphicsEnv.pinIntelDxgiVendor(env, HostGpu.INTEL)
        assertEquals("dxgi.customVendorId = 8086", env.get("DXVK_CONFIG"))
    }

    @Test
    fun pinIntelDxgiVendor_leavesAmdAlone() {
        val env = EnvVars()
        HostGraphicsEnv.pinIntelDxgiVendor(env, HostGpu.AMD)
        assertEquals("", env.get("DXVK_CONFIG"))
    }

    @Test
    fun pinIntelDxgiVendor_appendsToExistingConfig() {
        val env = EnvVars()
        env.put("DXVK_CONFIG", "\"dxgi.maxDeviceMemory = 512\"")
        HostGraphicsEnv.pinIntelDxgiVendor(env, HostGpu.INTEL)
        assertEquals(
            "dxgi.maxDeviceMemory = 512; dxgi.customVendorId = 8086",
            env.get("DXVK_CONFIG"),
        )
    }

    @Test
    fun shouldPinIntelDxgi_usesVulkanPropertyWhenDrmIsUnknown() {
        assertTrue(HostGraphicsEnv.shouldPinIntelDxgi(HostGpu.UNKNOWN, "intel"))
        assertFalse(HostGraphicsEnv.shouldPinIntelDxgi(HostGpu.UNKNOWN, ""))
        assertFalse(HostGraphicsEnv.shouldPinIntelDxgi(HostGpu.AMD, "intel"))
        assertTrue(HostGraphicsEnv.shouldPinIntelDxgi(HostGpu.INTEL, ""))
    }

    @Test
    fun pinIntelDxgiVendor_doesNotDuplicate() {
        val env = EnvVars()
        env.put("DXVK_CONFIG", "dxgi.customVendorId = 8086")
        HostGraphicsEnv.pinIntelDxgiVendor(env, HostGpu.INTEL)
        assertEquals("dxgi.customVendorId = 8086", env.get("DXVK_CONFIG"))
    }
}
