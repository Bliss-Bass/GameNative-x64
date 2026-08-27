package app.gamenative.utils

import android.os.Build

/**
 * Android host CPU ABI for launcher and container bootstrap.
 * ARM64 phones use Box64/FEX; x86_64 tablets run guest x86_64 natively.
 */
enum class HostCpu {
    ARM64,
    X86_64,
    ;

    val isX86_64: Boolean
        get() = this == X86_64

    companion object {
        @JvmStatic
        fun current(): HostCpu {
            for (abi in Build.SUPPORTED_ABIS) {
                if (abi.startsWith("x86")) {
                    return X86_64
                }
            }
            return ARM64
        }
    }
}
