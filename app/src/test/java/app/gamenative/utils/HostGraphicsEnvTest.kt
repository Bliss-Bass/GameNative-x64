package app.gamenative.utils

import com.winlator.core.envvars.EnvVars
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
}
