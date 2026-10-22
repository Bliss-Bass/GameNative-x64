package app.gamenative.ui.screen.linux

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.enums.LinuxSessionMode
import app.gamenative.linux.LinuxAppIcon
import app.gamenative.linux.LinuxAppScanner
import app.gamenative.linux.LinuxAppSettings
import app.gamenative.linux.LinuxDisplayScale
import app.gamenative.linux.LinuxSessions
import app.gamenative.linux.LinuxXServerView
import app.gamenative.ui.LinuxSessionActivity
import app.gamenative.ui.component.QuickMenuAdjustmentRow
import app.gamenative.ui.component.QuickMenuCloseButton
import app.gamenative.ui.component.QuickMenuDetailRow
import app.gamenative.ui.component.QuickMenuItem
import app.gamenative.ui.component.QuickMenuRailActionButton
import app.gamenative.ui.component.QuickMenuSectionHeader
import app.gamenative.ui.component.QuickMenuTabButton
import app.gamenative.ui.component.QuickMenuToggleRow
import app.gamenative.ui.component.focusRing
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.adaptivePanelWidth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TAB_APPS = 0
private const val TAB_SETTINGS = 1

/** Whether [event] opens or closes the menu: Back, Shift+Esc, or Esc while it is open. */
internal fun isLinuxMenuKey(event: KeyEvent, menuOpen: Boolean): Boolean =
    event.keyCode == KeyEvent.KEYCODE_BACK ||
        (event.keyCode == KeyEvent.KEYCODE_ESCAPE && (menuOpen || event.isShiftPressed))

/**
 * Toggles [menuOpen] for a menu key, once per press. Handled by hand rather than through
 * [BackHandler]: with predictive back on, a Back *key* that a view or the activity sees never
 * reaches the back dispatcher.
 */
internal fun toggleLinuxMenu(menuOpen: MutableState<Boolean>, event: KeyEvent) {
    val acts = if (event.keyCode == KeyEvent.KEYCODE_BACK) {
        event.action == KeyEvent.ACTION_UP && !event.isCanceled
    } else {
        event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
    }
    if (acts) menuOpen.value = !menuOpen.value
}

/**
 * [LinuxXServerView.keyInterceptor] for a window with a menu. While the menu is open presses are
 * kept from X but releases are not, so a modifier held as the menu opened does not stick.
 */
internal fun linuxMenuKeyInterceptor(menuOpen: MutableState<Boolean>): (KeyEvent) -> Boolean = { event ->
    when {
        isLinuxMenuKey(event, menuOpen.value) -> {
            toggleLinuxMenu(menuOpen, event)
            true
        }
        menuOpen.value -> event.action != KeyEvent.ACTION_UP
        else -> false
    }
}

/**
 * The Linux window's side menu, opened with Back or Shift+Esc like the in-game quick menu.
 *
 * Android gives a launcher one window per app, so this is where a second Linux app is opened from
 * while one is in front, and where the window's own settings live.
 *
 * @param sessionKey the session this window shows, as [LinuxSessions.keyFor] files it.
 * @param onShowKeyboard null when the window's view cannot take on-screen typing.
 */
@Composable
fun LinuxSessionMenu(
    visible: Boolean,
    sessionKey: String,
    title: String,
    onDismiss: () -> Unit,
    onShowKeyboard: (() -> Unit)?,
    onCloseApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_APPS) }
    val appsTabFocus = remember { FocusRequester() }
    val settingsTabFocus = remember { FocusRequester() }
    val accent = PluviaTheme.colors.accentPurple
    val closeItem = QuickMenuItem(
        id = 0,
        icon = Icons.AutoMirrored.Filled.ExitToApp,
        labelResId = R.string.linux_menu_close_app,
        accentColor = PluviaTheme.colors.accentDanger,
    )

    BackHandler(enabled = visible) { onDismiss() }

    // Take focus from the X view so keys and D-pad drive the menu; the panel is still sliding in.
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        repeat(3) {
            delay(80)
            val requester = if (selectedTab == TAB_APPS) appsTabFocus else settingsTabFocus
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(150)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.2f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(200)),
            exit = slideOutHorizontally(targetOffsetX = { -it }, animationSpec = tween(150)),
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            Surface(
                modifier = Modifier
                    .width(adaptivePanelWidth(400.dp))
                    .fillMaxHeight(),
                shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 24.dp,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        QuickMenuCloseButton(onClick = onDismiss)
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .width(64.dp)
                                .fillMaxHeight()
                                .focusGroup(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            QuickMenuTabButton(
                                icon = Icons.Default.Apps,
                                contentDescriptionResId = R.string.linux_menu_tab_apps,
                                selected = selectedTab == TAB_APPS,
                                accentColor = accent,
                                onSelected = { selectedTab = TAB_APPS },
                                modifier = Modifier.width(56.dp),
                                focusRequester = appsTabFocus,
                            )
                            QuickMenuTabButton(
                                icon = Icons.Default.Tune,
                                contentDescriptionResId = R.string.linux_menu_tab_settings,
                                selected = selectedTab == TAB_SETTINGS,
                                accentColor = accent,
                                onSelected = { selectedTab = TAB_SETTINGS },
                                modifier = Modifier.width(56.dp),
                                focusRequester = settingsTabFocus,
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 4.dp, vertical = 4.dp)
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
                            )
                            QuickMenuRailActionButton(
                                item = closeItem,
                                onClick = onCloseApp,
                                modifier = Modifier.width(56.dp),
                            )
                        }

                        Box(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
                        )

                        Box(modifier = Modifier.fillMaxSize()) {
                            when (selectedTab) {
                                TAB_APPS -> AppsTab(sessionKey = sessionKey, onOpened = onDismiss)
                                else -> SettingsTab(
                                    sessionKey = sessionKey,
                                    appLabel = title,
                                    onShowKeyboard = onShowKeyboard?.let { show -> { onDismiss(); show() } },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Installed Linux apps; picking one opens (or brings forward) its own window. */
@Composable
private fun AppsTab(sessionKey: String, onOpened: () -> Unit) {
    val context = LocalContext.current
    val apps by produceState<List<LinuxAppScanner.LinuxApp>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { runCatching { LinuxAppScanner.scan(context) }.getOrDefault(emptyList()) }
    }
    val running by LinuxSessions.sessions.collectAsState()
    val runningKeys = running.map { it.key }.toSet()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            QuickMenuSectionHeader(
                title = stringResource(R.string.linux_menu_apps_header),
                subtitle = stringResource(R.string.linux_menu_apps_subtitle),
            )
        }
        val list = apps
        when {
            list == null -> item {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            list.isEmpty() -> item {
                Text(
                    text = stringResource(R.string.linux_menu_apps_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                )
            }

            else -> items(list, key = { it.entryId }) { app ->
                val key = LinuxSessions.keyFor(app.entryId, app.launchArgv)
                val status = when (key) {
                    sessionKey -> stringResource(R.string.linux_menu_app_current)
                    in runningKeys -> stringResource(R.string.linux_menu_app_running)
                    else -> app.comment.orEmpty()
                }
                LinuxAppRow(
                    app = app,
                    status = status,
                    highlighted = key == sessionKey || key in runningKeys,
                    onClick = {
                        if (key != sessionKey) {
                            LinuxSessionActivity.open(context, app.launchArgv, app.entryId, app.name)
                        }
                        onOpened()
                    },
                )
            }
        }
    }
}

@Composable
private fun LinuxAppRow(
    app: LinuxAppScanner.LinuxApp,
    status: String,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val icon by produceState<Bitmap?>(initialValue = null, app.iconPath) {
        value = withContext(Dispatchers.IO) { runCatching { LinuxAppIcon.load(context, app.iconPath) }.getOrNull() }
    }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val accent = PluviaTheme.colors.accentPurple
    val shape = RoundedCornerShape(14.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(shape)
            .background(
                if (focused) accent.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
            )
            .focusRing(interactionSource, shape, width = 2.dp)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            icon?.let {
                Image(bitmap = it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(36.dp))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (focused) accent else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (status.isNotBlank()) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (highlighted) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** This window's size, the default for every app, and how closing behaves. */
@Composable
private fun SettingsTab(
    sessionKey: String,
    appLabel: String,
    onShowKeyboard: (() -> Unit)?,
) {
    val context = LocalContext.current
    val accent = PluviaTheme.colors.accentPurple
    val choices = LinuxDisplayScale.SCALE_CHOICES
    // Null first: "follow the default" is the bottom of this app's range.
    val appSteps: List<Int?> = listOf<Int?>(null) + choices

    var override by remember(sessionKey) { mutableStateOf(LinuxAppSettings.scaleOverride(context, sessionKey)) }
    var global by remember { mutableIntStateOf(PrefManager.linuxUiScalePercent) }
    var keepRunning by remember { mutableStateOf(PrefManager.linuxSessionMode.holdsWhenClosed) }

    fun setOverride(percent: Int?) {
        if (percent == override) return
        override = percent
        LinuxAppSettings.setScaleOverride(context, sessionKey, percent)
        LinuxSessions.applyScaleAsync(context, sessionKey)
    }

    fun setGlobal(percent: Int) {
        if (percent == global) return
        global = percent
        PrefManager.linuxUiScalePercent = percent
        LinuxSessions.applyScaleAsync(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        QuickMenuSectionHeader(title = stringResource(R.string.linux_menu_this_app), subtitle = appLabel)
        val appIndex = appSteps.indexOf(override).coerceAtLeast(0)
        QuickMenuAdjustmentRow(
            title = stringResource(R.string.linux_menu_app_scale),
            valueText = override?.let { stringResource(R.string.linux_scale_value, it) }
                ?: stringResource(R.string.linux_menu_app_scale_default, global),
            progress = appIndex / (appSteps.size - 1f),
            onDecrease = { setOverride(appSteps[(appIndex - 1).coerceAtLeast(0)]) },
            onIncrease = { setOverride(appSteps[(appIndex + 1).coerceAtMost(appSteps.lastIndex)]) },
            accentColor = accent,
            subtitle = stringResource(R.string.linux_menu_scale_hint),
        )
        if (onShowKeyboard != null) {
            QuickMenuDetailRow(
                title = stringResource(R.string.linux_menu_keyboard),
                subtitle = stringResource(R.string.linux_menu_keyboard_subtitle),
                accentColor = accent,
                onActivate = onShowKeyboard,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        QuickMenuSectionHeader(title = stringResource(R.string.linux_menu_all_apps))
        val globalIndex = choices.indexOf(global).coerceAtLeast(0)
        QuickMenuAdjustmentRow(
            title = stringResource(R.string.linux_menu_default_scale),
            valueText = if (global == LinuxDisplayScale.DEFAULT_SCALE_PERCENT) {
                stringResource(R.string.linux_scale_match, global)
            } else {
                stringResource(R.string.linux_scale_value, global)
            },
            progress = globalIndex / (choices.size - 1f),
            onDecrease = { setGlobal(choices[(globalIndex - 1).coerceAtLeast(0)]) },
            onIncrease = { setGlobal(choices[(globalIndex + 1).coerceAtMost(choices.lastIndex)]) },
            accentColor = accent,
            subtitle = stringResource(R.string.linux_menu_default_scale_hint),
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.linux_menu_keep_running),
            enabled = keepRunning,
            onToggle = {
                keepRunning = !keepRunning
                PrefManager.linuxSessionMode =
                    if (keepRunning) LinuxSessionMode.KEEP_RUNNING else LinuxSessionMode.CLOSE_WITH_APP
            },
            accentColor = accent,
            subtitle = stringResource(
                if (keepRunning) R.string.linux_session_mode_keep_summary else R.string.linux_session_mode_close_summary,
            ),
        )
    }
}
