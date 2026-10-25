package app.gamenative.ui.screen.linux

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.Context
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.enums.LinuxDisplayBackend
import app.gamenative.enums.LinuxSessionMode
import app.gamenative.linux.LinuxAppIcon
import app.gamenative.linux.LinuxAppIds
import app.gamenative.linux.LinuxDistroCatalog
import app.gamenative.linux.LinuxAppReconciler
import app.gamenative.linux.LinuxAppScanner
import app.gamenative.linux.LinuxAppStubs
import app.gamenative.linux.LinuxAppUninstaller
import app.gamenative.linux.LinuxDisplayScale
import app.gamenative.linux.LinuxDistros
import app.gamenative.linux.LinuxRootfs
import app.gamenative.linux.LinuxSessions
import app.gamenative.linux.LinuxStorage
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.automationIds
import app.gamenative.ui.util.pluviaTopSafeAreaPadding
import app.gamenative.utils.createLinuxAppShortcut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The applications installed in the Linux userland.
 *
 * Kept apart from the game library rather than folded into it as another source: a library
 * entry carries a Wine container, an install size and a store it came from, none of which a
 * native Linux app has. Tapping one starts a graphical session and runs it.
 */
@Composable
fun LinuxAppsScreen(
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit,
    onLaunch: (LinuxAppScanner.LinuxApp) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableIntStateOf(0) }

    // Edit mode: apps are selected rather than launched, and act together from the bar below.
    var editing by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<Int>()) }
    var uninstall by remember { mutableStateOf<UninstallRequest?>(null) }
    var working by remember { mutableStateOf<String?>(null) }

    // Which applications are up. Collected rather than read once: a session can end on its own
    // when the last window in it exits, and the row's controls must not outlive it.
    val sessions by LinuxSessions.sessions.collectAsStateWithLifecycle()

    // All-files access is granted in Settings, so the answer only changes while attention is
    // elsewhere. Keyed on window focus rather than the lifecycle: in desktop windowing both
    // windows are on screen at once and this one is never paused, so ON_RESUME never comes.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var storageGranted by remember { mutableStateOf(LinuxStorage.isGranted(context)) }
    LaunchedEffect(windowFocused) {
        if (windowFocused) storageGranted = LinuxStorage.isGranted(context)
    }

    // Reading a few dozen small files, off the main thread. Rescanned on request because
    // apt runs in the terminal, out of sight of this screen. Session leave / stop also
    // reconcile via [LinuxAppReconciler.request]; this path keeps the list itself current.
    val apps by produceState<List<LinuxAppScanner.LinuxApp>?>(initialValue = null, refreshKey) {
        val found = withContext(Dispatchers.IO) { LinuxAppScanner.scan(context) }
        value = found

        // After the list is on screen rather than before: taking back an entry for an application
        // that is gone can involve the package installer, and the user should not wait on it.
        withContext(Dispatchers.IO) { LinuxAppReconciler.reconcile(context, found) }
    }

    uninstall?.let { request ->
        UninstallDialog(
            request = request,
            onDismiss = { uninstall = null },
            onConfirm = {
                uninstall = null
                working = context.getString(R.string.linux_apps_uninstalling)
                scope.launch {
                    request.apps.forEach { app ->
                        LinuxSessions.stop(context, LinuxSessions.keyFor(app.entryId, app.launchArgv))
                    }
                    LinuxAppUninstaller.uninstall(context, request.plan)
                        .onSuccess {
                            SnackbarManager.show(
                                context.getString(R.string.linux_apps_uninstalled, request.apps.joinToString { it.name }),
                            )
                        }
                        .onFailure {
                            SnackbarManager.show(context.getString(R.string.linux_apps_uninstall_failed, it.message ?: it.toString()))
                        }
                    working = null
                    editing = false
                    selected = emptySet()
                    refreshKey++
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pluviaTopSafeAreaPadding()
            .automationIds()
            .testTag("linux_apps_screen"),
    ) {
        // Apps come from every supported distro, so any one of them installed is enough.
        val installed = remember(refreshKey) { LinuxDistros.integrated(context).isNotEmpty() }
        Header(
            onBack = onBack,
            onRefresh = if (installed && !editing) {{ refreshKey++ }} else null,
            running = sessions.size,
            onStopAll = { LinuxSessions.stopAll(context) },
            editing = editing,
            selectedCount = selected.size,
            onEdit = if (installed && !apps.isNullOrEmpty()) {
                {
                    editing = !editing
                    selected = emptySet()
                }
            } else {
                null
            },
        )

        working?.let { message ->
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("linux_apps_working"))
            }
        }

        if (installed && !storageGranted) {
            StorageBanner(onGrant = { LinuxStorage.requestAccess(context) })
        }

        if (installed && !editing) {
            ScaleRow()
            SessionBackendRow()
            SessionModeRow()
        }

        val chosen = apps.orEmpty().filter { it.id in selected }
        if (editing) {
            EditBar(
                anyRunning = chosen.any { app ->
                    sessions.any { it.key == LinuxSessions.keyFor(app.entryId, app.launchArgv) }
                },
                stubsSupported = LinuxAppStubs.isSupported(context),
                enabled = chosen.isNotEmpty() && working == null,
                onStop = {
                    chosen.forEach { LinuxSessions.stop(context, LinuxSessions.keyFor(it.entryId, it.launchArgv)) }
                },
                onRemoveFromDrawer = {
                    scope.launch {
                        chosen.forEach { app ->
                            if (withContext(Dispatchers.IO) { LinuxAppStubs.isInstalled(context, app) }) {
                                removeFromDrawer(context, app)
                            }
                        }
                        refreshKey++
                    }
                },
                onUninstall = {
                    working = context.getString(R.string.linux_apps_checking)
                    scope.launch {
                        val plan = LinuxAppUninstaller.plan(context, chosen)
                        working = null
                        uninstall = UninstallRequest(chosen, plan)
                    }
                },
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            val found = apps
            when {
                !LinuxRootfs.isSupported() -> Message(stringResource(R.string.linux_apps_unsupported))

                !installed -> Message(
                    text = stringResource(R.string.linux_apps_not_installed),
                    actionLabel = stringResource(R.string.linux_apps_open_terminal),
                    onAction = onOpenTerminal,
                )

                found == null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                found.isEmpty() -> Message(
                    text = stringResource(R.string.linux_apps_empty),
                    actionLabel = stringResource(R.string.linux_apps_open_terminal),
                    onAction = onOpenTerminal,
                )

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    val showDistro = found.map { it.distroId }.distinct().size > 1
                    item(key = "running") { LinuxRunningSection() }
                    items(found, key = { it.id }) { app ->
                        val session = sessions.firstOrNull {
                            it.key == LinuxSessions.keyFor(app.entryId, app.launchArgv)
                        }
                        val stubsSupported = LinuxAppStubs.isSupported(context)
                        val inDrawer by produceState(false, app.entryId, refreshKey) {
                            value = if (!stubsSupported) {
                                false
                            } else {
                                withContext(Dispatchers.IO) { LinuxAppStubs.isInstalled(context, app) }
                            }
                        }
                        AppRow(
                            app = app,
                            session = session,
                            onClick = {
                                if (editing) {
                                    selected = if (app.id in selected) selected - app.id else selected + app.id
                                } else {
                                    onLaunch(app)
                                }
                            },
                            selected = (app.id in selected).takeIf { editing },
                            onStop = { LinuxSessions.stop(context, it.key) },
                            onRestart = { LinuxSessions.restart(context, it.key) },
                            onPin = { scope.launch { createLinuxAppShortcut(context, app) } },
                            inDrawer = inDrawer.takeIf { stubsSupported },
                            onToggleDrawer = if (stubsSupported) {
                                {
                                    scope.launch {
                                        if (inDrawer) {
                                            removeFromDrawer(context, app)
                                        } else {
                                            addToDrawer(context, app)
                                        }
                                        refreshKey++
                                    }
                                }
                            } else {
                                null
                            },
                            showDistro = showDistro,
                        )
                    }
                }
            }
        }
    }
}

/** Apps the user chose to uninstall, and what that will remove. */
private data class UninstallRequest(
    val apps: List<LinuxAppScanner.LinuxApp>,
    val plan: LinuxAppUninstaller.Plan,
)

/** What edit mode does with the selected apps. */
@Composable
private fun EditBar(
    anyRunning: Boolean,
    stubsSupported: Boolean,
    enabled: Boolean,
    onStop: () -> Unit,
    onRemoveFromDrawer: () -> Unit,
    onUninstall: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            enabled = enabled && anyRunning,
            onClick = onStop,
            modifier = Modifier.testTag("linux_apps_action_stop"),
        ) { Text(stringResource(R.string.linux_apps_action_stop)) }
        if (stubsSupported) {
            TextButton(
                enabled = enabled,
                onClick = onRemoveFromDrawer,
                modifier = Modifier.testTag("linux_apps_action_undrawer"),
            ) { Text(stringResource(R.string.linux_apps_action_undrawer)) }
        }
        Button(
            enabled = enabled,
            onClick = onUninstall,
            modifier = Modifier.padding(start = 8.dp).testTag("linux_apps_action_uninstall"),
        ) { Text(stringResource(R.string.linux_apps_action_uninstall)) }
    }
}

/**
 * Says by name what will go before anything does: removing a package removes every app it ships,
 * which may be more than the ones selected.
 */
@Composable
private fun UninstallDialog(request: UninstallRequest, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val plan = request.plan
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.linux_apps_uninstall_title)) },
        text = {
            Column {
                if (plan.isEmpty) {
                    Text(stringResource(R.string.linux_apps_uninstall_nothing))
                }
                plan.packages.filterValues { it.isNotEmpty() }.forEach { (distroId, names) ->
                    Text(
                        stringResource(R.string.linux_apps_uninstall_packages, LinuxDistroCatalog.nameOf(distroId), names.joinToString()),
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                if (plan.files.isNotEmpty()) {
                    Text(
                        stringResource(R.string.linux_apps_uninstall_files, plan.files.joinToString { it.name }),
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                if (plan.unknown.isNotEmpty()) {
                    Text(
                        stringResource(R.string.linux_apps_uninstall_unknown, plan.unknown.joinToString { it.name }),
                        style = MaterialTheme.typography.bodySmall,
                        color = PluviaTheme.colors.textMuted,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !plan.isEmpty,
                onClick = onConfirm,
                modifier = Modifier.automationIds().testTag("dialog_confirm"),
            ) { Text(stringResource(R.string.linux_apps_uninstall_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.automationIds().testTag("dialog_cancel")) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * Builds a stub for [app] and asks for it to be installed.
 *
 * The prompt the user then sees is Android's, and there is no way for an unprivileged app to skip
 * it; the ROM's privileged helper is what makes this silent.
 */
private suspend fun addToDrawer(context: Context, app: LinuxAppScanner.LinuxApp) {
    LinuxAppStubs.add(context, app)
        .onSuccess {
            SnackbarManager.show(context.getString(R.string.stub_added, app.name))
        }
        .onFailure {
            Timber.e(it, "[LinuxAppsScreen]: could not build a stub for %s", app.name)
            SnackbarManager.show(context.getString(R.string.stub_add_failed, app.name))
        }
}

/** Takes [app]'s stub out of the all-apps list and remembers the user asked it gone. */
private suspend fun removeFromDrawer(context: Context, app: LinuxAppScanner.LinuxApp) {
    LinuxAppStubs.remove(context, app.entryId, LinuxAppStubs.packageNameFor(app), suppress = true)
        .onSuccess {
            SnackbarManager.show(context.getString(R.string.stub_removed, app.name))
        }
        .onFailure {
            Timber.e(it, "[LinuxAppsScreen]: could not remove stub for %s", app.name)
            SnackbarManager.show(context.getString(R.string.stub_remove_failed, app.name))
        }
}

/**
 * How large Linux apps draw, as a percentage of Android's own UI scale.
 *
 * Here rather than in Settings because it is only meaningful once there is a userland to apply it
 * to, and this is the screen someone is on when they notice the size is wrong.
 *
 * Running sessions without their own size are retold at once, which GTK apps follow live through
 * XSETTINGS; anything that reads DPI only at startup picks it up when restarted. A session's own
 * override is set from its window's menu ([LinuxSessionMenu]).
 */
@Composable
private fun ScaleRow() {
    val context = LocalContext.current
    var percent by rememberSaveable { mutableIntStateOf(PrefManager.linuxUiScalePercent) }
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.linux_scale_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.linux_scale_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = PluviaTheme.colors.textMuted,
            )
        }

        Box {
            Button(onClick = { expanded = true }) {
                Text(stringResource(R.string.linux_scale_value, percent))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                LinuxDisplayScale.SCALE_CHOICES.forEach { choice ->
                    DropdownMenuItem(
                        text = {
                            // The default is worth naming: "100%" alone does not say what it is
                            // 100% of, and matching Android is the whole point of the number.
                            val label = if (choice == LinuxDisplayScale.DEFAULT_SCALE_PERCENT) {
                                stringResource(R.string.linux_scale_match, choice)
                            } else {
                                stringResource(R.string.linux_scale_value, choice)
                            }
                            Text(label)
                        },
                        onClick = {
                            percent = choice
                            PrefManager.linuxUiScalePercent = choice
                            LinuxSessions.applyScaleAsync(context)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

/**
 * Offers the all-files grant, without which the userland sees none of Android's files.
 *
 * A banner rather than a prompt on entry: the userland is useful without it, and this is a
 * special access that only Settings can give, so interrupting someone who came here to launch
 * something would be the wrong trade.
 */
@Composable
private fun StorageBanner(onGrant: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onGrant)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.FolderOff,
            contentDescription = null,
            tint = PluviaTheme.colors.accentCyan,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
            Text(
                text = stringResource(R.string.linux_storage_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.linux_storage_body),
                style = MaterialTheme.typography.bodySmall,
                color = PluviaTheme.colors.textMuted,
            )
        }
    }
}

/**
 * Which X transport hosts freeform Linux apps.
 *
 * Beside the session-lifetime control: switching backends mid-flight would leave Android-X and
 * VNC sessions mixed in the registry, so a change stops everything and the next open uses the
 * new path.
 */
@Composable
private fun SessionBackendRow() {
    val context = LocalContext.current
    var backend by rememberSaveable { mutableStateOf(PrefManager.linuxDisplayBackend) }
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.linux_display_backend_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(backend.summaryRes),
                style = MaterialTheme.typography.bodySmall,
                color = PluviaTheme.colors.textMuted,
            )
        }

        Box {
            Button(onClick = { expanded = true }) {
                Text(stringResource(backend.titleRes))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                LinuxDisplayBackend.entries.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(stringResource(choice.titleRes)) },
                        onClick = {
                            if (choice != backend) {
                                backend = choice
                                PrefManager.linuxDisplayBackend = choice
                                LinuxSessions.stopAll(context)
                            }
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

/**
 * How long a session lives after its window closes.
 *
 * Beside the size control for the same reason: it is only meaningful once there is a userland,
 * and this is the screen someone is on when they wonder why an app they closed is still listed as
 * running -- or why reopening it is slow.
 */
@Composable
private fun SessionModeRow() {
    var mode by rememberSaveable { mutableStateOf(PrefManager.linuxSessionMode) }
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.linux_session_mode_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(mode.summaryRes),
                style = MaterialTheme.typography.bodySmall,
                color = PluviaTheme.colors.textMuted,
            )
        }

        Box {
            Button(onClick = { expanded = true }) {
                Text(stringResource(mode.titleRes))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                LinuxSessionMode.entries.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(stringResource(choice.titleRes)) },
                        onClick = {
                            mode = choice
                            PrefManager.linuxSessionMode = choice
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: LinuxAppScanner.LinuxApp,
    session: LinuxSessions.Snapshot?,
    onClick: () -> Unit,
    /** Null outside edit mode; otherwise whether this app is selected. */
    selected: Boolean?,
    onStop: (LinuxSessions.Snapshot) -> Unit,
    onRestart: (LinuxSessions.Snapshot) -> Unit,
    onPin: () -> Unit,
    /** Null when stubs are unavailable; otherwise whether this app is in the drawer. */
    inDrawer: Boolean?,
    onToggleDrawer: (() -> Unit)?,
    /** Whether apps from more than one distro are listed, so each row says which it is from. */
    showDistro: Boolean = false,
) {
    // Automation id: distro and entry kept apart, since an entry may contain dots and dashes.
    val tagKey = "${app.distroId}__${LinuxAppIds.entryOf(app.entryId)}"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("linux_app_$tagKey")
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onClick() },
                modifier = Modifier.testTag("linux_app_select_$tagKey"),
            )
        }
        AppIcon(iconPath = app.iconPath, distroId = app.distroId)

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp),
        ) {
            Text(
                text = app.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // A running session is what someone is looking for when they came here to stop
            // one, so it displaces the description while it lasts.
            val detail = when {
                session != null -> stringResource(R.string.linux_session_running, session.display)
                app.comment != null -> app.comment
                app.terminal -> stringResource(R.string.linux_apps_terminal_app)
                else -> null
            }
            val subtitle = listOfNotNull(
                LinuxDistroCatalog.nameOf(app.distroId).takeIf { showDistro },
                detail,
            ).joinToString(" · ").ifEmpty { null }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (session != null) {
                        PluviaTheme.colors.accentCyan
                    } else {
                        PluviaTheme.colors.textMuted
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // In edit mode the row is a selection; what to do with it is in the bar above the list.
        if (selected != null) return@Row

        // Only while there is a session to act on: a stop button for something that is not
        // running would be furniture, and a restart would be a launch by another name.
        if (session != null) {
            IconButton(onClick = { onRestart(session) }, modifier = Modifier.size(44.dp).testTag("linux_app_restart_$tagKey")) {
                Icon(
                    imageVector = Icons.Filled.RestartAlt,
                    contentDescription = stringResource(R.string.linux_session_restart),
                    tint = Color.White.copy(alpha = 0.7f),
                )
            }
            IconButton(onClick = { onStop(session) }, modifier = Modifier.size(44.dp).testTag("linux_app_stop_$tagKey")) {
                Icon(
                    imageVector = Icons.Filled.StopCircle,
                    contentDescription = stringResource(R.string.linux_session_stop),
                    tint = PluviaTheme.colors.accentCyan,
                )
            }
        }

        if (onToggleDrawer != null && inDrawer != null) {
            IconButton(
                onClick = onToggleDrawer,
                modifier = Modifier.size(44.dp)
                    .testTag(if (inDrawer) "linux_app_undrawer_$tagKey" else "linux_app_drawer_$tagKey"),
            ) {
                Icon(
                    imageVector = if (inDrawer) {
                        Icons.Filled.RemoveCircleOutline
                    } else {
                        Icons.Filled.Apps
                    },
                    contentDescription = stringResource(
                        if (inDrawer) R.string.remove_from_app_list else R.string.add_to_app_list,
                    ),
                    tint = Color.White.copy(alpha = 0.7f),
                )
            }
        }

        IconButton(onClick = onPin, modifier = Modifier.size(44.dp).testTag("linux_app_pin_$tagKey")) {
            Icon(
                imageVector = Icons.Filled.AddToHomeScreen,
                contentDescription = stringResource(R.string.linux_apps_pin),
                tint = Color.White.copy(alpha = 0.7f),
            )
        }
    }
}

/** The entry's icon, or a stand-in when it is missing or in a format Android cannot read. */
@Composable
private fun AppIcon(iconPath: String?, distroId: String) {
    val context = LocalContext.current

    val bitmap by produceState<ImageBitmap?>(null, iconPath, distroId) {
        value = withContext(Dispatchers.IO) { LinuxAppIcon.load(context, iconPath, distroId)?.asImageBitmap() }
    }

    val image = bitmap
    if (image != null) {
        androidx.compose.foundation.Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(40.dp),
        )
    } else {
        Icon(
            imageVector = Icons.Filled.Terminal,
            contentDescription = null,
            tint = PluviaTheme.colors.accentCyan,
            modifier = Modifier.size(40.dp).padding(4.dp),
        )
    }
}

@Composable
private fun Header(
    onBack: () -> Unit,
    onRefresh: (() -> Unit)?,
    running: Int,
    onStopAll: () -> Unit,
    editing: Boolean,
    selectedCount: Int,
    /** Null when there is nothing to edit. */
    onEdit: (() -> Unit)?,
) {
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
            text = if (editing) {
                stringResource(R.string.linux_apps_selected, selectedCount)
            } else {
                stringResource(R.string.linux_apps_title)
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )

        if (onEdit != null) {
            if (editing) {
                TextButton(onClick = onEdit, modifier = Modifier.testTag("linux_apps_edit")) {
                    Text(stringResource(R.string.linux_apps_edit_done))
                }
            } else {
                IconButton(onClick = onEdit, modifier = Modifier.size(44.dp).testTag("linux_apps_edit")) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.linux_apps_edit),
                        tint = Color.White.copy(alpha = 0.8f),
                    )
                }
            }
        }

        // Held sessions are otherwise invisible from here: the list shows what is installed, and
        // a lifetime the user cannot see or end is not one they can be asked to choose.
        if (running > 0) {
            Text(
                text = stringResource(R.string.linux_session_count, running),
                style = MaterialTheme.typography.bodySmall,
                color = PluviaTheme.colors.accentCyan,
            )
            IconButton(onClick = onStopAll, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = Icons.Filled.StopCircle,
                    contentDescription = stringResource(R.string.linux_session_stop_all),
                    tint = PluviaTheme.colors.accentCyan,
                )
            }
        }

        if (onRefresh != null) {
            IconButton(onClick = onRefresh, modifier = Modifier.size(44.dp).testTag("linux_apps_refresh")) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.linux_apps_refresh),
                    tint = Color.White.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun Message(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = PluviaTheme.colors.textMuted,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 24.dp)) {
                Text(actionLabel)
            }
        }
    }
}
