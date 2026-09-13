package app.gamenative.utils

import android.content.Context
import com.winlator.container.Container
import com.winlator.container.ContainerData
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HostContainerPolicyTest {

    @Test
    fun defaultProtonWineVersion_returnsX86_64OnX86Host() {
        assertEquals(HostContainerPolicy.PROTON_X86_64, HostContainerPolicy.defaultProtonWineVersion(HostCpu.X86_64))
    }

    @Test
    fun defaultProtonWineVersion_returnsArm64ecOnArmHost() {
        assertEquals(HostContainerPolicy.PROTON_ARM64EC, HostContainerPolicy.defaultProtonWineVersion(HostCpu.ARM64))
    }

    @Test
    fun mapWineVersionForHost_rewritesArm64ecProton10OnX86Host() {
        assertEquals(
            HostContainerPolicy.PROTON_X86_64,
            HostContainerPolicy.mapWineVersionForHost("proton-10.0-arm64ec-2", HostCpu.X86_64),
        )
    }

    @Test
    fun mapWineVersionForHost_leavesArm64ecOnArmHost() {
        assertEquals(
            "proton-10.0-arm64ec-2",
            HostContainerPolicy.mapWineVersionForHost("proton-10.0-arm64ec-2", HostCpu.ARM64),
        )
    }

    @Test
    fun adaptContainerForHost_rewritesWineAndWrapperGraphics() {
        ShadowBuild.setSupportedAbis(arrayOf("x86_64", "arm64-v8a", "x86"))
        val context: Context = RuntimeEnvironment.getApplication()
        val container = Container("STEAM_220").apply {
            wineVersion = "proton-10.0-arm64ec-2"
            graphicsDriver = "Wrapper"
            graphicsDriverConfig = "version=System,presentMode=mailbox"
        }

        assertTrue(HostContainerPolicy.adaptContainerForHost(context, container))
        assertEquals(HostContainerPolicy.PROTON_X86_64, container.wineVersion)
        assertEquals("System", container.graphicsDriver)
        assertTrue(container.graphicsDriverConfig.contains("version=System"))
    }

    @Test
    fun mapWineVersionForHost_rewritesProton9Arm64ec() {
        assertEquals(
            "proton-9.0-x86_64",
            HostContainerPolicy.mapWineVersionForHost("proton-9.0-arm64ec", HostCpu.X86_64),
        )
    }

    @Test
    fun adaptContainerForHost_rewritesWrapperV2FromBestConfig() {
        ShadowBuild.setSupportedAbis(arrayOf("x86_64", "arm64-v8a", "x86"))
        val context: Context = RuntimeEnvironment.getApplication()
        val container = Container("STEAM_964800").apply {
            wineVersion = "proton-9.0-arm64ec"
            graphicsDriver = "wrapper-v2"
            graphicsDriverConfig = "version=System,presentMode=mailbox"
        }

        assertTrue(HostContainerPolicy.adaptContainerForHost(context, container))
        assertEquals("proton-9.0-x86_64", container.wineVersion)
        assertEquals("System", container.graphicsDriver)
    }

    @Test
    fun adaptContainerForHost_noOpWhenAlreadyX86_64() {
        ShadowBuild.setSupportedAbis(arrayOf("x86_64", "arm64-v8a", "x86"))
        val context: Context = RuntimeEnvironment.getApplication()
        val container = Container("STEAM_220").apply {
            wineVersion = HostContainerPolicy.PROTON_X86_64
            graphicsDriver = "System"
        }

        assertFalse(HostContainerPolicy.adaptContainerForHost(context, container))
    }

    @Test
    fun adaptContainerForHost_rewritesTurnipToSystem() {
        ShadowBuild.setSupportedAbis(arrayOf("x86_64", "arm64-v8a", "x86"))
        val context: Context = RuntimeEnvironment.getApplication()
        val container = Container("STEAM_570").apply {
            wineVersion = "proton-9.0-arm64ec"
            graphicsDriver = "turnip"
            graphicsDriverConfig = "version=turnip25.3.0,presentMode=mailbox"
        }

        assertTrue(HostContainerPolicy.adaptContainerForHost(context, container))
        assertEquals("proton-9.0-x86_64", container.wineVersion)
        assertEquals("System", container.graphicsDriver)
        assertTrue(container.graphicsDriverConfig.contains("version=System"))
    }

    @Test
    fun adaptBestConfigJson_rewritesArm64ecAndWrapperOnX86() {
        ShadowBuild.setSupportedAbis(arrayOf("x86_64", "arm64-v8a", "x86"))
        val json = JSONObject().apply {
            put("wineVersion", "proton-9.0-arm64ec")
            put("graphicsDriver", "wrapper-v2")
            put("graphicsDriverConfig", "version=wrapper-v2,presentMode=mailbox")
        }

        HostContainerPolicy.adaptBestConfigJson(json)

        assertEquals("proton-9.0-x86_64", json.getString("wineVersion"))
        assertEquals("System", json.getString("graphicsDriver"))
        assertTrue(json.getString("graphicsDriverConfig").contains("version=System"))
    }

    @Test
    fun adaptBestConfigJson_leavesArm64ecOnArmHost() {
        ShadowBuild.setSupportedAbis(arrayOf("arm64-v8a", "armeabi-v7a"))
        val json = JSONObject().apply {
            put("wineVersion", "proton-9.0-arm64ec")
            put("graphicsDriver", "wrapper-v2")
        }

        HostContainerPolicy.adaptBestConfigJson(json)

        assertEquals("proton-9.0-arm64ec", json.getString("wineVersion"))
        assertEquals("wrapper-v2", json.getString("graphicsDriver"))
    }

    @Test
    fun applyBestConfigMap_remapsWineAndGraphicsOnX86Host() {
        ShadowBuild.setSupportedAbis(arrayOf("x86_64", "arm64-v8a", "x86"))
        val updated = ContainerUtils.applyBestConfigMapToContainerData(
            containerData = ContainerData(),
            bestConfigMap = mapOf(
                "wineVersion" to "proton-10.0-arm64ec-2",
                "graphicsDriver" to "Wrapper-leegao",
            ),
        )

        assertEquals(HostContainerPolicy.PROTON_X86_64, updated.wineVersion)
        assertEquals("System", updated.graphicsDriver)
    }
}
