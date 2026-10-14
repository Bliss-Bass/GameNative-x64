package app.gamenative.utils

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HostGpuTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun card(name: String, vendor: String?) {
        val device = File(temp.root, "$name/device").apply { mkdirs() }
        vendor?.let { File(device, "vendor").writeText(it) }
    }

    @Test
    fun detectsIntel() {
        card("card1", "0x8086\n")
        assertEquals(HostGpu.INTEL, HostGpu.detectIn(temp.root))
    }

    @Test
    fun detectsAmd() {
        card("card0", "0x1002\n")
        assertEquals(HostGpu.AMD, HostGpu.detectIn(temp.root))
    }

    @Test
    fun ignoresConnectorNodes() {
        // Connector dirs sit beside the card and would otherwise be read first.
        card("card1-eDP-1", "0x10de\n")
        card("card1", "0x8086\n")
        assertEquals(HostGpu.INTEL, HostGpu.detectIn(temp.root))
    }

    @Test
    fun skipsCardsWithoutAKnownVendor() {
        card("card0", "0xdead\n")
        card("card1", "0x1002\n")
        assertEquals(HostGpu.AMD, HostGpu.detectIn(temp.root))
    }

    @Test
    fun unknownWhenVendorMissing() {
        card("card0", null)
        assertEquals(HostGpu.UNKNOWN, HostGpu.detectIn(temp.root))
    }

    @Test
    fun unknownWhenDrmClassDirAbsent() {
        assertEquals(HostGpu.UNKNOWN, HostGpu.detectIn(File(temp.root, "missing")))
    }

    private val icds = listOf("intel_icd.x86_64.json", "lvp_icd.x86_64.json", "radeon_icd.x86_64.json")
        .map { File("/icd.d", it) }

    @Test
    fun intelSeesOnlyItsOwnIcd() {
        assertEquals(listOf(icds[0]), HostGpu.INTEL.vulkanIcdsFrom(icds))
    }

    @Test
    fun amdSeesOnlyRadv() {
        assertEquals(listOf(icds[2]), HostGpu.AMD.vulkanIcdsFrom(icds))
    }

    @Test
    fun unknownVendorKeepsEveryIcd() {
        assertEquals(icds, HostGpu.UNKNOWN.vulkanIcdsFrom(icds))
        assertEquals(icds, HostGpu.NVIDIA.vulkanIcdsFrom(icds))
    }

    @Test
    fun missingHardwareIcdFallsBackToEveryIcd() {
        val softwareOnly = listOf(icds[1])
        assertEquals(softwareOnly, HostGpu.INTEL.vulkanIcdsFrom(softwareOnly))
    }

    @Test
    fun mapsVulkanHardwareProperty() {
        assertEquals(HostGpu.INTEL, HostGpu.fromVulkanHardware("intel"))
        assertEquals(HostGpu.AMD, HostGpu.fromVulkanHardware("radeon"))
        assertEquals(HostGpu.UNKNOWN, HostGpu.fromVulkanHardware(""))
    }
}
