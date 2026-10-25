package app.gamenative.ui.screen.linux

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gamenative.R
import app.gamenative.linux.LinuxDistroCatalog
import app.gamenative.linux.LinuxSessions
import app.gamenative.service.LinuxVtService
import app.gamenative.ui.theme.PluviaTheme

/**
 * Everything Linux that is running, by distro: app windows and the desktop, with Restart and
 * Stop, and the Ctrl+Alt+F4 to F6 console shells, with Restart. Nothing at all while nothing runs.
 *
 * Shown on Linux Apps and Linux Distros, so a session left running in the background, which has
 * no window to close, can always be found and ended.
 */
@Composable
fun LinuxRunningSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sessions by LinuxSessions.sessions.collectAsStateWithLifecycle()
    val consoles by LinuxVtService.consoles.collectAsStateWithLifecycle()
    if (sessions.isEmpty() && consoles.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth().testTag("linux_running")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.linux_running_title),
                style = MaterialTheme.typography.titleSmall,
                color = PluviaTheme.colors.accentCyan,
                modifier = Modifier.weight(1f),
            )
            if (sessions.isNotEmpty()) {
                TextButton(
                    onClick = { LinuxSessions.stopAll(context) },
                    modifier = Modifier.testTag("linux_running_stop_all"),
                ) { Text(stringResource(R.string.linux_session_stop_all)) }
            }
        }

        val distros = (sessions.map { it.distroId } + consoles.map { it.distroId }).distinct()
        for (distroId in distros) {
            Text(
                text = LinuxDistroCatalog.nameOf(distroId),
                style = MaterialTheme.typography.labelMedium,
                color = PluviaTheme.colors.textMuted,
                modifier = Modifier.padding(start = 20.dp, top = 8.dp),
            )
            sessions.filter { it.distroId == distroId }.forEach { session ->
                val desktop = session.key == LinuxSessions.DESKTOP_KEY
                val tag = sessionTag(session.key)
                RunningRow(
                    icon = if (desktop) Icons.Filled.DesktopWindows else Icons.Filled.Web,
                    title = if (desktop) stringResource(R.string.linux_running_desktop) else session.label,
                    detail = stringResource(
                        if (session.watched) R.string.linux_running_window_open else R.string.linux_running_background,
                        session.display,
                    ),
                    tag = "linux_running_$tag",
                ) {
                    IconButton(
                        onClick = { LinuxSessions.restart(context, session.key) },
                        modifier = Modifier.size(44.dp).testTag("linux_running_restart_$tag"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.RestartAlt,
                            contentDescription = stringResource(R.string.linux_running_restart),
                            tint = Color.White.copy(alpha = 0.7f),
                        )
                    }
                    IconButton(
                        onClick = { LinuxSessions.stop(context, session.key) },
                        modifier = Modifier.size(44.dp).testTag("linux_running_stop_$tag"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.StopCircle,
                            contentDescription = stringResource(R.string.linux_running_stop),
                            tint = PluviaTheme.colors.accentCyan,
                        )
                    }
                }
            }
            consoles.filter { it.distroId == distroId }.forEach { console ->
                RunningRow(
                    icon = Icons.Filled.Terminal,
                    title = stringResource(R.string.linux_running_console, console.vt),
                    detail = "${console.user} · ${console.shell.substringAfterLast('/')}",
                    tag = "linux_running_console_${console.vt}",
                ) {
                    // A console always gets a shell back when it is shown, as a login prompt
                    // would, so there is no stopping one for good; Restart is what there is.
                    IconButton(
                        onClick = { LinuxVtService.restartConsole(context, console.vt) },
                        modifier = Modifier.size(44.dp).testTag("linux_running_console_restart_${console.vt}"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.RestartAlt,
                            contentDescription = stringResource(R.string.linux_running_restart),
                            tint = Color.White.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RunningRow(
    icon: ImageVector,
    title: String,
    detail: String,
    tag: String,
    actions: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = PluviaTheme.colors.accentCyan,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = PluviaTheme.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        actions()
    }
}

/** An automation id for a session key, which may hold colons and other characters ids should not. */
internal fun sessionTag(key: String): String =
    key.map { if (it.isLetterOrDigit() || it == '-' || it == '.') it else '_' }.joinToString("")
