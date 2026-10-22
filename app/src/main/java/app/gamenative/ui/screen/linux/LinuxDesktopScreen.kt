package app.gamenative.ui.screen.linux

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.gamenative.R
import app.gamenative.linux.LinuxDesktopSessions
import app.gamenative.linux.LinuxDistroCatalog
import app.gamenative.linux.LinuxRootfs
import app.gamenative.linux.LinuxSession
import app.gamenative.linux.LinuxSessions
import app.gamenative.linux.LinuxXServerView
import app.gamenative.ui.util.pluviaSafeDrawingPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber

private const val RESIZE_DEBOUNCE_MS = 150L
/** Below this we still resize X to the view — never leave a stale larger screen. */
private const val MIN_SESSION_PX = 64
/** Initial open waits for a usable freeform client area. */
private const val MIN_OPEN_PX = 320
/**
 * After the debounced resize, re-read [contentSize] a few times and re-apply if the
 * frame moved again (freeform often emits a trailing size after the drag ends).
 */
private val FRAME_VERIFY_DELAYS_MS = longArrayOf(200L, 500L, 1_000L)

/**
 * A window onto a Linux session.
 *
 * A viewer, not an owner: the session belongs to [LinuxSessions], which is what lets one outlive
 * this composable and lets a second launch of the same application find the session it already
 * has rather than start a rival copy. What happens when this window closes is the user's choice,
 * expressed as [app.gamenative.enums.LinuxSessionMode] and applied by the registry.
 *
 * @param argv the program this session is for, or null for a bare desktop.
 * @param entryId the desktop entry the program came from, which is what keys the session.
 * @param label how the session names itself in the shade and in the session list.
 * @param menuOpen whether [LinuxSessionMenu] is showing; hoisted so the activity can open it from
 * keys it sees before the X view does.
 */
@Composable
fun LinuxDesktopScreen(
    argv: String? = null,
    entryId: String? = null,
    label: String? = null,
    menuOpen: MutableState<Boolean> = remember { mutableStateOf(false) },
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val metrics = context.resources.displayMetrics
    val key = remember(entryId, argv) { LinuxSessions.keyFor(entryId, argv) }

    var session by remember { mutableStateOf<LinuxSession?>(null) }
    var linuxView by remember { mutableStateOf<View?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    // Size the X screen to this composable's client area. safeDrawing clears freeform
    // captionBar + status/nav/cutout so onSizeChanged is the true client area.
    // Open once; later size changes call session.resize so GTK reflows (not presenter-scale).
    var contentSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(key, contentSize) {
        if (contentSize.width < MIN_SESSION_PX || contentSize.height < MIN_SESSION_PX) {
            return@LaunchedEffect
        }

        val active = session
        if (active != null) {
            // Debounce freeform drag jitter. Always apply the latest size — skipping when
            // under 320px left the X screen larger than the Android frame.
            delay(RESIZE_DEBOUNCE_MS)
            if (contentSize.width < MIN_SESSION_PX || contentSize.height < MIN_SESSION_PX) {
                return@LaunchedEffect
            }
            Timber.i(
                "[LinuxDesktopScreen]: resizing session to content %dx%d",
                contentSize.width,
                contentSize.height,
            )
            active.resize(contentSize.width, contentSize.height)
            // Trailing verifies: freeform often posts another size after the drag ends;
            // session also reconciles against the live view, but this catches Compose-only
            // updates when the SurfaceView size lagged.
            for (verifyDelay in FRAME_VERIFY_DELAYS_MS) {
                delay(verifyDelay)
                if (contentSize.width < MIN_SESSION_PX || contentSize.height < MIN_SESSION_PX) {
                    return@LaunchedEffect
                }
                Timber.d(
                    "[LinuxDesktopScreen]: verify frame still %dx%d",
                    contentSize.width,
                    contentSize.height,
                )
                active.resize(contentSize.width, contentSize.height)
            }
            return@LaunchedEffect
        }

        if (contentSize.width < MIN_OPEN_PX || contentSize.height < MIN_OPEN_PX) {
            return@LaunchedEffect
        }

        // A desktop in another distro does not need Ubuntu at all, let alone its session packages.
        val ubuntuSession = argv != null ||
            LinuxDesktopSessions.target(context).distroId == LinuxDistroCatalog.UBUNTU
        if (ubuntuSession) {
            LinuxRootfs.ensureDisplaySession(context) { status = it.message }
                .onFailure {
                    Timber.e(it, "[LinuxDesktopScreen]: could not complete the session install")
                    error = it.message
                    return@LaunchedEffect
                }
        }
        status = null

        Timber.i(
            "[LinuxDesktopScreen]: opening session at content %dx%d",
            contentSize.width,
            contentSize.height,
        )
        LinuxSessions.open(
            context = context,
            key = key,
            label = label ?: context.getString(R.string.linux_session_notification_title),
            argv = argv,
            width = contentSize.width,
            height = contentSize.height,
            densityDpi = metrics.densityDpi,
        )
            .onSuccess { opened ->
                withContext(Dispatchers.IO) { LinuxSessions.applyScale(context, key, metrics.densityDpi) }
                LinuxSessions.attach(key)
                session = opened
            }
            .onFailure { error = it.message }
    }

    // A session stopped from elsewhere (a Linux Services job stop, the session list) never reaches
    // the view's exit callback; without this its window would stay open on a dead session and a
    // relaunch with the same key would only bring that window forward.
    LaunchedEffect(key, session) {
        if (session == null) return@LaunchedEffect
        LinuxSessions.sessions.first { list -> list.any { it.key == key } }
        LinuxSessions.sessions.first { list -> list.none { it.key == key } }
        Timber.i("[LinuxDesktopScreen]: session %s ended elsewhere, closing", key)
        onExit()
    }

    DisposableEffect(key) {
        onDispose { LinuxSessions.detach(context, key) }
    }

    // Back opens the menu rather than closing the window; closing is in the menu and the caption.
    BackHandler(enabled = session != null && !menuOpen.value) { menuOpen.value = true }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pluviaSafeDrawingPadding()
            .background(Color.Black)
            .onSizeChanged { contentSize = it },
        contentAlignment = Alignment.Center,
    ) {
        val active = session
        when {
            error != null -> Message(error!!)
            active == null -> Starting(status)
            else -> AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    active.createView(viewContext) { failure ->
                        if (failure != null) {
                            error = failure.message
                            return@createView
                        }
                        LinuxSessions.stop(context, key)
                        onExit()
                    }.apply {
                        (this as? LinuxXServerView)?.keyInterceptor = linuxMenuKeyInterceptor(menuOpen)
                        requestFocus()
                    }.also { linuxView = it }
                },
                onRelease = {
                    (it as? LinuxXServerView)?.keyInterceptor = null
                    if (linuxView === it) linuxView = null
                    active.releaseView(it)
                },
            )
        }

        if (session != null) {
            LinuxSessionMenu(
                visible = menuOpen.value,
                sessionKey = key,
                title = label ?: context.getString(R.string.linux_session_notification_title),
                onDismiss = {
                    menuOpen.value = false
                    linuxView?.requestFocus()
                },
                onShowKeyboard = (linuxView as? LinuxXServerView)?.let { view -> { view.showIme() } },
                onCloseApp = {
                    menuOpen.value = false
                    LinuxSessions.stop(context, key)
                    onExit()
                },
            )
        }
    }
}

@Composable
private fun Starting(status: String?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator()
        Text(text = status ?: stringResource(R.string.linux_desktop_starting), color = Color.White)
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text = text,
        color = Color.White,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(32.dp),
    )
}
