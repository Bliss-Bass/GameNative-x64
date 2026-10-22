package app.gamenative.ui.screen.linux

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.linux.LinuxDesktopSessions
import app.gamenative.linux.LinuxDistro
import app.gamenative.linux.LinuxDistroCatalog
import app.gamenative.linux.LinuxDistros
import app.gamenative.linux.LinuxRootfs
import app.gamenative.linux.LinuxSessions
import app.gamenative.linux.LinuxVtConsoles
import app.gamenative.platform.PlatformCapabilities
import app.gamenative.service.LinuxVtService
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.pluviaTopSafeAreaPadding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Installs and removes Linux distros, picks the desktop the session window opens, and assigns
 * distros to the Ctrl+Alt+F4 to F6 consoles.
 *
 * The consoles section is only there on Bass OS, where the platform grants GameNative the
 * consoles ([PlatformCapabilities.Snapshot.linuxVt]); everything else works on any install.
 */
@Composable
fun LinuxDistrosScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val running by DistroWork.running.collectAsState()
    val failure by DistroWork.failure.collectAsState()
    // Bumped when work finishes, so every "is it installed" below is asked again.
    val version by DistroWork.version.collectAsState()
    var confirmRemove by remember { mutableStateOf<LinuxDistro?>(null) }

    confirmRemove?.let { distro ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(stringResource(R.string.linux_distro_remove_title, distro.displayName)) },
            text = { Text(stringResource(R.string.linux_distro_remove_message, distro.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = null
                    DistroWork.run(context, distro.id) {
                        if (distro.id == LinuxDistroCatalog.UBUNTU) LinuxSessions.stopAll(context)
                        LinuxDistros.uninstall(context, distro.id)
                        Result.success(Unit)
                    }
                }) { Text(stringResource(R.string.linux_distro_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pluviaTopSafeAreaPadding(),
    ) {
        Header(onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            failure?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }

            Section(R.string.linux_distros_section, R.string.linux_distros_section_hint)
            val defaultId = remember(version) { LinuxDistros.defaultId(context) }
            LinuxDistroCatalog.all.forEach { distro ->
                val installed = remember(version, distro.id) { LinuxDistros.isInstalled(context, distro.id) }
                DistroRow(
                    distro = distro,
                    installed = installed,
                    isDefault = installed && distro.id == defaultId,
                    work = running?.takeIf { it.key == distro.id },
                    busy = running != null,
                    onInstall = { DistroWork.run(context, distro.id) { LinuxDistros.install(context, distro.id, it) } },
                    onRemove = { confirmRemove = distro },
                    onMakeDefault = {
                        LinuxDistros.setDefault(context, distro.id)
                        DistroWork.bump()
                    },
                )
            }

            Divider()
            DesktopSection(version = version, work = running, onWork = { key, block -> DistroWork.run(context, key, block) })

            Divider()
            ConsolesSection(version = version)
        }
    }
}

@Composable
private fun DistroRow(
    distro: LinuxDistro,
    installed: Boolean,
    isDefault: Boolean,
    work: DistroWork.State?,
    busy: Boolean,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
    onMakeDefault: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = distro.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                val status = when {
                    isDefault -> stringResource(R.string.linux_distro_default)
                    installed -> stringResource(R.string.linux_distro_installed)
                    else -> stringResource(R.string.linux_distro_size, distro.release, distro.downloadMb, distro.installedMb)
                }
                Text(
                    text = if (installed) "${distro.release} · $status" else status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDefault) PluviaTheme.colors.accentCyan else PluviaTheme.colors.textMuted,
                )
            }
            if (installed) {
                if (!isDefault) {
                    TextButton(enabled = !busy, onClick = onMakeDefault) {
                        Text(stringResource(R.string.linux_distro_make_default))
                    }
                }
                OutlinedButton(enabled = !busy, onClick = onRemove) {
                    Text(stringResource(R.string.linux_distro_remove))
                }
            } else {
                Button(enabled = !busy && LinuxRootfs.isSupported(), onClick = onInstall) {
                    Text(stringResource(R.string.linux_distro_install))
                }
            }
        }
        work?.let { Progress(it) }
    }
}

@Composable
private fun DesktopSection(
    version: Int,
    work: DistroWork.State?,
    onWork: (String, suspend ((LinuxRootfs.Progress) -> Unit) -> Result<Unit>) -> Unit,
) {
    val context = LocalContext.current
    Section(R.string.linux_desktop_section, R.string.linux_desktop_section_hint)

    val installed = remember(version) { LinuxDistros.installed(context) }
    if (installed.isEmpty()) return
    var distroId by remember(version) { mutableStateOf(LinuxDesktopSessions.desktopDistro(context)) }
    var session by remember(version, distroId) { mutableStateOf(LinuxDesktopSessions.selected(context, distroId)) }
    val sessionInstalled = remember(version, distroId, session) {
        LinuxDesktopSessions.isInstalled(context, distroId, session)
    }

    LabeledChoice(
        label = stringResource(R.string.linux_desktop_distro),
        value = LinuxDistroCatalog.nameOf(distroId),
        choices = installed.map { it.displayName to it.id },
        onChoose = {
            distroId = it
            LinuxDesktopSessions.setDesktopDistro(context, it)
        },
    )
    LabeledChoice(
        label = stringResource(R.string.linux_desktop_session),
        value = if (sessionInstalled) {
            session.displayName
        } else {
            stringResource(R.string.linux_desktop_session_not_installed, session.displayName)
        },
        choices = LinuxDesktopSessions.Session.entries.map { it.displayName to it },
        onChoose = {
            session = it
            // Remembered even before it is installed, so installing it next makes it the one used.
            LinuxDesktopSessions.select(context, distroId, it)
        },
    )

    val key = "session:$distroId:${session.id}"
    val builtIn = distroId == LinuxDistroCatalog.UBUNTU && session == LinuxDesktopSessions.Session.OPENBOX
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        val chosen = session
        val distro = distroId
        when {
            builtIn -> Unit
            sessionInstalled -> OutlinedButton(enabled = work == null, onClick = {
                onWork(key) { LinuxDesktopSessions.remove(context, distro, chosen) }
            }) { Text(stringResource(R.string.linux_desktop_session_remove, chosen.displayName)) }
            else -> Button(enabled = work == null, onClick = {
                onWork(key) { progress ->
                    LinuxDesktopSessions.install(context, distro, chosen, progress)
                        .onSuccess { LinuxDesktopSessions.select(context, distro, chosen) }
                }
            }) { Text(stringResource(R.string.linux_desktop_session_install, chosen.displayName)) }
        }
    }
    work?.takeIf { it.key == key }?.let {
        Box(modifier = Modifier.padding(horizontal = 20.dp)) { Progress(it) }
    }
}

@Composable
private fun ConsolesSection(version: Int) {
    val context = LocalContext.current
    Section(R.string.linux_vt_section, R.string.linux_vt_section_hint)

    if (!PlatformCapabilities.get(context).linuxVt) {
        Text(
            text = stringResource(R.string.linux_vt_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = PluviaTheme.colors.textMuted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        return
    }

    val installed = remember(version) { LinuxDistros.installed(context) }
    // Bumped on every change so the rows reread what was saved.
    var edits by remember { mutableStateOf(0) }
    val off = stringResource(R.string.linux_vt_off)
    val defaultShell = stringResource(R.string.linux_vt_shell_default)

    fun save(vt: Int, assignment: LinuxVtConsoles.Assignment?) {
        LinuxVtConsoles.set(context, vt, assignment)
        LinuxVtService.reloadIfSupported(context)
        edits++
    }

    for (vt in LinuxVtConsoles.CONSOLES) {
        val assignment = remember(version, edits, vt) { LinuxVtConsoles.get(context, vt) }
        val rootfs = assignment?.let { LinuxDistros.installedRootfs(context, it.distroId) }
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(
                text = stringResource(R.string.linux_vt_console, vt),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            LabeledChoice(
                label = stringResource(R.string.linux_desktop_distro),
                value = assignment?.let { LinuxDistroCatalog.nameOf(it.distroId) } ?: off,
                choices = listOf(off to null) + installed.map { it.displayName to it.id },
                onChoose = { id -> save(vt, id?.let { LinuxVtConsoles.Assignment(distroId = it) }) },
            )
            if (assignment != null && rootfs != null) {
                val shells = remember(rootfs) { LinuxDistros.shells(rootfs) }
                LabeledChoice(
                    label = stringResource(R.string.terminal_shell),
                    value = assignment.shell ?: defaultShell,
                    choices = listOf(defaultShell to null) + shells.map { it to it },
                    onChoose = { save(vt, assignment.copy(shell = it)) },
                )
                val users = remember(rootfs) { LinuxVtConsoles.users(rootfs) }
                if (users.size > 1) {
                    LabeledChoice(
                        label = stringResource(R.string.linux_vt_user),
                        value = assignment.user,
                        choices = users.map { it to it },
                        onChoose = { save(vt, assignment.copy(user = it)) },
                    )
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        OutlinedButton(onClick = {
            val first = LinuxVtConsoles.get(context, LinuxVtConsoles.CONSOLES.first)
            for (vt in LinuxVtConsoles.CONSOLES) LinuxVtConsoles.set(context, vt, first)
            LinuxVtService.reloadIfSupported(context)
            edits++
        }) { Text(stringResource(R.string.linux_vt_use_on_all)) }
    }
}

@Composable
private fun <T> LabeledChoice(label: String, value: String, choices: List<Pair<String, T>>, onChoose: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = PluviaTheme.colors.textMuted,
            modifier = Modifier.weight(1f),
        )
        Box {
            TextButton(onClick = { expanded = true }) { Text(value) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                choices.forEach { (name, choice) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            expanded = false
                            onChoose(choice)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Progress(state: DistroWork.State) {
    val fraction = state.progress?.fraction ?: -1f
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        if (fraction >= 0f) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        state.progress?.message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = PluviaTheme.colors.textMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun Section(title: Int, hint: Int) {
    Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp)) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            color = PluviaTheme.colors.accentCyan,
        )
        Text(
            text = stringResource(hint),
            style = MaterialTheme.typography.bodySmall,
            color = PluviaTheme.colors.textMuted,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun Header(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.back),
                tint = Color.White.copy(alpha = 0.8f),
            )
        }
        Text(
            text = stringResource(R.string.linux_distros_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * The one install or removal in flight. Process-scoped rather than the screen's, so leaving the
 * screen does not abandon a download halfway, and coming back shows it still going.
 */
private object DistroWork {
    data class State(val key: String, val progress: LinuxRootfs.Progress?)

    val running = MutableStateFlow<State?>(null)
    val failure = MutableStateFlow<String?>(null)
    val version = MutableStateFlow(0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun bump() {
        version.value++
    }

    fun run(
        context: android.content.Context,
        key: String,
        block: suspend ((LinuxRootfs.Progress) -> Unit) -> Result<Unit>,
    ) {
        if (running.value != null) return
        val app = context.applicationContext
        running.value = State(key, null)
        failure.value = null
        scope.launch {
            val result = runCatching { block { progress -> running.value = State(key, progress) } }
                .getOrElse { Result.failure(it) }
            result.onFailure {
                val name = LinuxDistroCatalog.byId(key)?.displayName ?: key.substringAfterLast(':')
                failure.value = app.getString(R.string.linux_distro_failed, name, it.message ?: it.toString())
            }
            running.value = null
            bump()
        }
    }
}
