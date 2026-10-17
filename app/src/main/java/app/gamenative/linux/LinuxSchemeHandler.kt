package app.gamenative.linux

import java.io.File
import timber.log.Timber

/**
 * Hands a browser sign-in link back to the program that opened it.
 *
 * Cursor finishes login by sending the browser to `cursor://…`. Each app has its own
 * X server, so Firefox cannot see Cursor's window. Both share the rootfs and
 * `XDG_RUNTIME_DIR=/tmp`, which is where Cursor's first process listens. A second
 * `cursor --no-sandbox <url>` connects to that socket, passes the token, and exits.
 * The flag has to be on this command: a desktop-file exec does not go through
 * [LinuxProgramLauncher], which is the only other place it is added.
 */
object LinuxSchemeHandler {

    private const val DESKTOP_ID = "gamenative-scheme-open.desktop"
    private val SCHEME_LINES = listOf(
        "x-scheme-handler/cursor=$DESKTOP_ID",
        "x-scheme-handler/vscode=$DESKTOP_ID",
    )

    fun install(rootfs: File) {
        val bin = File(rootfs, "usr/local/bin/gamenative-scheme-open")
        bin.parentFile?.mkdirs()
        bin.writeText(OPENER)
        bin.setExecutable(true, false)

        for (relative in listOf(
            "usr/local/share/applications/$DESKTOP_ID",
            "root/.local/share/applications/$DESKTOP_ID",
        )) {
            val desktop = File(rootfs, relative)
            desktop.parentFile?.mkdirs()
            desktop.writeText(DESKTOP)
        }

        for (relative in listOf("root/.config/mimeapps.list", "etc/xdg/mimeapps.list")) {
            val mime = File(rootfs, relative)
            mime.parentFile?.mkdirs()
            val existing = if (mime.isFile) mime.readText() else ""
            mime.writeText(mimeappsWithSchemes(existing))
        }
        Timber.i("[LinuxSchemeHandler]: cursor:// and vscode:// open the running app")
    }

    /**
     * The command to run inside an already-open session, or null when [incoming] is just
     * the app being brought forward again.
     *
     * A bare `cursor://` URL is prefixed with the program that session was started with,
     * so the second process is Cursor rather than an attempt to execute the URL.
     */
    internal fun forwardArgv(runningArgv: String?, incoming: String?): String? {
        val next = incoming?.trim().orEmpty()
        if (next.isEmpty()) return null
        val current = runningArgv?.trim().orEmpty()
        val hasUrl = next.contains("://")
        val extraArgs = current.isNotEmpty() && next != current &&
            next.startsWith(current) && next.getOrNull(current.length)?.isWhitespace() == true
        if (!hasUrl && !extraArgs) return null
        if (!next.contains(" ") && hasUrl) {
            if (current.isEmpty() || current.contains("://")) return null
            return "$current $next"
        }
        return next
    }

    internal fun mimeappsWithSchemes(existing: String): String {
        val lines = if (existing.isBlank()) mutableListOf() else existing.lines().toMutableList()
        val missing = SCHEME_LINES.filter { wanted -> lines.none { it.trim() == wanted } }
        if (missing.isEmpty()) {
            return if (existing.isEmpty() || existing.endsWith("\n")) existing else "$existing\n"
        }
        val header = lines.indexOfFirst { it.trim() == "[Default Applications]" }
        if (header == -1) {
            if (lines.lastOrNull().orEmpty().isNotBlank()) lines.add("")
            lines.add("[Default Applications]")
            lines.addAll(missing)
        } else {
            lines.addAll(header + 1, missing)
        }
        return lines.joinToString("\n").trimEnd() + "\n"
    }

    private val OPENER = """
        #!/bin/sh
        # Written by GameNative. A second Cursor process forwards the sign-in URL
        # to the one already running, then exits.
        if [ -x /usr/share/cursor/cursor ]; then
          exec /usr/share/cursor/cursor --no-sandbox "${'$'}@"
        fi
        if command -v cursor >/dev/null 2>&1; then
          exec cursor --no-sandbox "${'$'}@"
        fi
        echo "gamenative-scheme-open: Cursor is not installed" >&2
        exit 1
    """.trimIndent() + "\n"

    private val DESKTOP = """
        [Desktop Entry]
        Version=1.0
        Type=Application
        Name=Cursor
        NoDisplay=true
        Terminal=false
        StartupNotify=false
        Exec=/usr/local/bin/gamenative-scheme-open %u
        MimeType=x-scheme-handler/cursor;x-scheme-handler/vscode;
    """.trimIndent() + "\n"
}
