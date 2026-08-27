package app.gamenative.utils

import android.content.Context
import com.winlator.container.Container
import com.winlator.core.KeyValueSet
import timber.log.Timber

/**
 * Host-aware defaults and migration for Bionic containers.
 * x86_64 tablets run x86_64 Proton natively; ARM64 phones use arm64ec + FEX/Box64.
 */
object HostContainerPolicy {
    const val PROTON_X86_64 = "proton-10.0-4-x86_64-1"
    const val PROTON_ARM64EC = "proton-10.0-arm64ec-2"

    private val ARM64EC_TO_X86_64 = mapOf(
        "proton-9.0-arm64ec" to "proton-9.0-x86_64",
        "proton-10.0-arm64ec-2" to PROTON_X86_64,
        "proton-10.0-4-arm64ec-1" to PROTON_X86_64,
        "proton-11.0-1-arm64ec-1" to "proton-11.0-1-x86_64-1",
    )

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

    /**
     * Rewrites arm64ec-oriented container fields on x86_64 hosts.
     * Returns true when the container was mutated (caller should persist).
     */
    fun adaptContainerForHost(context: Context, container: Container): Boolean {
        if (!HostCpu.current().isX86_64) return false

        var changed = false

        val mappedWine = mapWineVersionForHost(container.wineVersion)
        if (mappedWine != container.wineVersion) {
            Timber.i("HostContainerPolicy: wineVersion %s -> %s", container.wineVersion, mappedWine)
            container.wineVersion = mappedWine
            changed = true
        }

        if (container.graphicsDriver.equals("Wrapper", ignoreCase = true)) {
            Timber.i("HostContainerPolicy: graphicsDriver Wrapper -> System (x86_64 host)")
            container.graphicsDriver = "System"
            val kvs = KeyValueSet(container.graphicsDriverConfig)
            kvs.put("version", "System")
            container.graphicsDriverConfig = kvs.toString()
            changed = true
        }

        return changed
    }
}
