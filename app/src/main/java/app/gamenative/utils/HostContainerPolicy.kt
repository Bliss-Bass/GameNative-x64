package app.gamenative.utils

import android.content.Context
import com.winlator.container.Container
import com.winlator.core.KeyValueSet
import org.json.JSONObject
import timber.log.Timber

/**
 * Host-aware defaults and migration for Bionic containers.
 * x86_64 tablets run x86_64 Proton natively; ARM64 phones use arm64ec + FEX/Box64.
 *
 * Community "best config" and PrefManager defaults are ARM-first. Every create,
 * apply, launch, and Proton-download path must run these remaps so no title can
 * persist or fetch arm64ec Proton on an x86_64 device.
 */
object HostContainerPolicy {
    const val PROTON_X86_64 = "proton-10.0-4-x86_64-1"
    const val PROTON_ARM64EC = "proton-10.0-arm64ec-2"
    const val GRAPHICS_SYSTEM = "System"

    private val ARM64EC_TO_X86_64 = mapOf(
        "proton-9.0-arm64ec" to "proton-9.0-x86_64",
        "proton-10.0-arm64ec-2" to PROTON_X86_64,
        "proton-10.0-4-arm64ec-1" to PROTON_X86_64,
        "proton-11.0-1-arm64ec-1" to "proton-11.0-1-x86_64-1",
    )

    fun isX86Host(): Boolean = HostCpu.current().isX86_64

    fun defaultProtonWineVersion(): String =
        defaultProtonWineVersion(HostCpu.current())

    fun defaultProtonWineVersion(host: HostCpu): String =
        if (host.isX86_64) PROTON_X86_64 else PROTON_ARM64EC

    fun mapWineVersionForHost(wineVersion: String): String =
        mapWineVersionForHost(wineVersion, HostCpu.current())

    fun mapWineVersionForHost(wineVersion: String, host: HostCpu): String {
        if (!host.isX86_64 || !wineVersion.contains("arm64ec", ignoreCase = true)) {
            return wineVersion
        }
        return ARM64EC_TO_X86_64[wineVersion]
            ?: wineVersion.replace("arm64ec", "x86_64", ignoreCase = true)
    }

    fun isArmGraphicsDriver(graphicsDriver: String): Boolean {
        val d = graphicsDriver.lowercase()
        return d == "wrapper" || d.startsWith("wrapper-") ||
            d == "turnip" || d.startsWith("turnip-") ||
            d.startsWith("sd-8-elite")
    }

    fun mapGraphicsDriverForHost(graphicsDriver: String): String =
        mapGraphicsDriverForHost(graphicsDriver, HostCpu.current())

    fun mapGraphicsDriverForHost(graphicsDriver: String, host: HostCpu): String {
        if (!host.isX86_64) return graphicsDriver
        return if (graphicsDriver.equals(GRAPHICS_SYSTEM, ignoreCase = true)) {
            graphicsDriver
        } else {
            GRAPHICS_SYSTEM
        }
    }

    /**
     * Rewrites ARM-first fields in a community / best-config JSON before it is
     * validated, applied, or used to enqueue Proton / FEX / Turnip downloads.
     */
    fun adaptBestConfigJson(json: JSONObject) {
        if (!isX86Host()) return

        val wine = json.optString("wineVersion", "")
        if (wine.isNotEmpty()) {
            val mappedWine = mapWineVersionForHost(wine)
            if (mappedWine != wine) {
                Timber.i("HostContainerPolicy: config wineVersion %s -> %s", wine, mappedWine)
                json.put("wineVersion", mappedWine)
            }
        }

        val gfx = json.optString("graphicsDriver", "")
        val mappedGfx = mapGraphicsDriverForHost(gfx)
        if (gfx.isNotEmpty() && mappedGfx != gfx) {
            Timber.i("HostContainerPolicy: config graphicsDriver %s -> %s", gfx, mappedGfx)
            json.put("graphicsDriver", mappedGfx)
            val cfg = json.optString("graphicsDriverConfig", "")
            if (cfg.isNotEmpty()) {
                val kvs = KeyValueSet(cfg)
                kvs.put("version", GRAPHICS_SYSTEM)
                json.put("graphicsDriverConfig", kvs.toString())
            }
        }
    }

    /**
     * Rewrites arm64ec-oriented container fields on x86_64 hosts.
     * Returns true when the container was mutated (caller should persist).
     */
    fun adaptContainerForHost(context: Context, container: Container): Boolean {
        if (!isX86Host()) return false

        var changed = false

        val mappedWine = mapWineVersionForHost(container.wineVersion)
        if (mappedWine != container.wineVersion) {
            Timber.i("HostContainerPolicy: wineVersion %s -> %s", container.wineVersion, mappedWine)
            container.wineVersion = mappedWine
            changed = true
        }

        val mappedGfx = mapGraphicsDriverForHost(container.graphicsDriver)
        if (mappedGfx != container.graphicsDriver) {
            Timber.i(
                "HostContainerPolicy: graphicsDriver %s -> %s (x86_64 host)",
                container.graphicsDriver,
                mappedGfx,
            )
            container.graphicsDriver = mappedGfx
            val kvs = KeyValueSet(container.graphicsDriverConfig)
            kvs.put("version", GRAPHICS_SYSTEM)
            container.graphicsDriverConfig = kvs.toString()
            changed = true
        }

        return changed
    }
}
