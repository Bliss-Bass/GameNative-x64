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
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.Context
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.enums.LinuxSessionMode
import app.gamenative.linux.LinuxAppIcon
import app.gamenative.linux.LinuxAppReconciler
import app.gamenative.linux.LinuxAppScanner
import app.gamenative.linux.LinuxAppStubs
import app.gamenative.linux.LinuxDisplayScale
import app.gamenative.linux.LinuxRootfs
import app.gamenative.linux.LinuxSessions
import app.gamenative.linux.LinuxStorage
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.ui.theme.PluviaTheme
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
    var confirmReset by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }

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
    // apt runs in the terminal, out of sight of this screen.
    val apps by produceState<List<LinuxAppScanner.LinuxApp>?>(initialValue = null, refreshKey) {
        val found = withContext(Dispatchers.IO) { LinuxAppScanner.scan(context) }
        value = found

        // After the list is on screen rather than before: taking back an entry for an application
        // that is gone can involve the package installer, and the user should not wait on it.
        withContext(Dispatchers.IO) { LinuxAppReconciler.reconcile(context, found) }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { if (!resetting) confirmReset = false },
            title = { Text(stringResource(R.string.linux_apps_reset_title)) },
            text = { Text(stringResource(R.string.linux_apps_reset_message)) },
            confirmButton = {
                TextButton(
                    enabled = !resetting,
                    onClick = {
                        resetting = true
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                LinuxSessions.stopAll(context)
                                LinuxRootfs.uninstall(context)
                            }
                            resetting = false
                            confirmReset = false
                            refreshKey++
                        }
                    },
                ) {
                    Text(stringResource(R.string.linux_apps_reset_confirm))
                }
            },
            dismissButton = {
                TextButton(enabled = !resetting, onClick = { confirmReset = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pluviaTopSafeAreaPadding(),
    ) {
        val installed = LinuxRootfs.isInstalled(context)
        Header(
            onBack = onBack,
            onRefresh = if (installed) {{ refreshKey++ }} else null,
            onReset = if (installed && !resetting) {{ confirmReset = true }} else null,
            running = sessions.size,
            onStopAll = { LinuxSessions.stopAll(context) },
        )

        if (resetting) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(R.string.linux_apps_resetting),
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
            return@Column
        }

        if (installed && !storageGranted) {
            StorageBanner(onGrant = { LinuxStorage.requestAccess(context) })
        }

        if (installed) {
            ScaleRow()
            SessionModeRow()
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
                            onClick = { onLaunch(app) },
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
                        )
                    }
                }
            }
        }
    }
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
 * Takes effect when a session next starts: the X server is told its DPI on the command line, and
 * a running session's clients have already sized their windows from it.
 */
@Composable
private fun ScaleRow() {
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
    onStop: (LinuxSessions.Snapshot) -> Unit,
    onRestart: (LinuxSessions.Snapshot) -> Unit,
    onPin: () -> Unit,
    /** Null when stubs are unavailable; otherwise whether this app is in the drawer. */
    inDrawer: Boolean?,
    onToggleDrawer: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(iconPath = app.iconPath)

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
            val subtitle = when {
                session != null -> stringResource(R.string.linux_session_running, session.display)
                app.comment != null -> app.comment
                app.terminal -> stringResource(R.string.linux_apps_terminal_app)
                else -> null
            }
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

        // Only while there is a session to act on: a stop button for something that is not
        // running would be furniture, and a restart would be a launch by another name.
        if (session != null) {
            IconButton(onClick = { onRestart(session) }, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = Icons.Filled.RestartAlt,
                    contentDescription = stringResource(R.string.linux_session_restart),
                    tint = Color.White.copy(alpha = 0.7f),
                )
            }
            IconButton(onClick = { onStop(session) }, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = Icons.Filled.StopCircle,
                    contentDescription = stringResource(R.string.linux_session_stop),
                    tint = PluviaTheme.colors.accentCyan,
                )
            }
        }

        if (onToggleDrawer != null && inDrawer != null) {
            IconButton(onClick = onToggleDrawer, modifier = Modifier.size(44.dp)) {
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

        IconButton(onClick = onPin, modifier = Modifier.size(44.dp)) {
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
private fun AppIcon(iconPath: String?) {
    val context = LocalContext.current

    val bitmap by produceState<ImageBitmap?>(null, iconPath) {
        value = withContext(Dispatchers.IO) { LinuxAppIcon.load(context, iconPath)?.asImageBitmap() }
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
    onReset: (() -> Unit)?,
    running: Int,
    onStopAll: () -> Unit,
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
            text = stringResource(R.string.linux_apps_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )

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
            IconButton(onClick = onRefresh, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.linux_apps_refresh),
                    tint = Color.White.copy(alpha = 0.8f),
                )
            }
        }

        if (onReset != null) {
            IconButton(onClick = onReset, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = Icons.Filled.DeleteForever,
                    contentDescription = stringResource(R.string.linux_apps_reset),
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
