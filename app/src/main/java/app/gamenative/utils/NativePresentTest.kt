package app.gamenative.utils

import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import java.io.File

/**
 * Detects and launches the x86_64 DRI3/present smoke container (`vkcube`).
 *
 * The custom-game folder ships a Termux-built Android ELF named `vkcube`. Launch bypasses Wine
 * via [EnvVars] `GUEST_PROGRAM_LAUNCHER_COMMAND` (semicolon-separated argv), which
 * [com.winlator.xenvironment.components.BionicProgramLauncherComponent] already honors.
 */
object NativePresentTest {
    const val FOLDER_NAME = "vkcube-debug"
    const val BINARY_NAME = "vkcube"
    /** Stable custom-game id so redeploys keep the same library entry. */
    const val STABLE_APP_ID = 424242

    /** Relative path used as [Container.executablePath]. */
    const val EXECUTABLE_PATH = BINARY_NAME

    @JvmStatic
    fun isVkcubeBinary(file: File): Boolean =
        file.isFile && file.name.equals(BINARY_NAME, ignoreCase = true)

    @JvmStatic
    fun findVkcubeInFolder(folder: File): File? {
        if (!folder.isDirectory) return null
        val direct = File(folder, BINARY_NAME)
        if (isVkcubeBinary(direct)) return direct
        return folder.listFiles()?.firstOrNull { isVkcubeBinary(it) }
    }

    /**
     * Shared storage often drops the execute bit. Copy into the app files dir when needed so
     * [linker64] can run the ELF.
     */
    @JvmStatic
    fun ensureRunnable(context: android.content.Context, source: File): File {
        require(source.isFile) { "vkcube missing: ${source.absolutePath}" }
        if (source.canExecute() && source.absolutePath.startsWith(context.filesDir.absolutePath)) {
            return source
        }
        val destDir = File(context.filesDir, "vktest")
        destDir.mkdirs()
        val dest = File(destDir, BINARY_NAME)
        if (!dest.exists() || dest.length() != source.length() || dest.lastModified() < source.lastModified()) {
            source.copyTo(dest, overwrite = true)
        }
        dest.setReadable(true, false)
        dest.setExecutable(true, false)
        return dest
    }

    @JvmStatic
    fun isPresentTestContainer(container: Container, gameFolderPath: String?): Boolean {
        val exe = container.executablePath.trim().replace('\\', '/')
        if (exe.equals(BINARY_NAME, ignoreCase = true) || exe.endsWith("/$BINARY_NAME", ignoreCase = true)) {
            return true
        }
        if (gameFolderPath.isNullOrBlank()) return false
        return findVkcubeInFolder(File(gameFolderPath)) != null &&
            (exe.isEmpty() || exe.equals(BINARY_NAME, ignoreCase = true))
    }

    /**
     * Builds `GUEST_PROGRAM_LAUNCHER_COMMAND` for [ProcessHelper] (linker64 is prepended on
     * modern Android). Default window matches the docs' presentation benchmarks.
     */
    @JvmStatic
    fun applyLaunchOverride(
        envVars: EnvVars,
        vkcube: File,
        width: Int = 1280,
        height: Int = 800,
        frameCount: Int = 0,
    ) {
        require(vkcube.isFile) { "vkcube missing: ${vkcube.absolutePath}" }
        vkcube.setExecutable(true, false)
        val parts = mutableListOf(
            vkcube.absolutePath,
            "--width", width.toString(),
            "--height", height.toString(),
        )
        // --c 0 = run until killed; omit only if frameCount < 0
        if (frameCount >= 0) {
            parts += listOf("--c", frameCount.toString())
        }
        envVars.put("GUEST_PROGRAM_LAUNCHER_COMMAND", parts.joinToString(";"))
        // Avoid Wine forcing GLX; vkcube is Vulkan/X11.
        envVars.remove("WINE_X11FORCEGLX")
        envVars.remove("REDIRECT_EXEC__PROC_SELF_EXE")
    }
}
