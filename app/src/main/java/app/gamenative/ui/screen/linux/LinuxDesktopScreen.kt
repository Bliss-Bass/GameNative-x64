package app.gamenative.ui.screen.linux

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.gamenative.R
import app.gamenative.linux.LinuxRootfs
import app.gamenative.linux.LinuxSession
import app.gamenative.linux.LinuxSessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

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
 */
@Composable
fun LinuxDesktopScreen(
    argv: String? = null,
    entryId: String? = null,
    label: String? = null,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val metrics = context.resources.displayMetrics
    val key = remember(entryId, argv) { LinuxSessions.keyFor(entryId, argv) }

    var session by remember { mutableStateOf<LinuxSession?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(key) {
        // Covers a userland installed before a package was added to the session, which is
        // an apt run rather than a reinstall, and can take long enough to need a message.
        LinuxRootfs.ensureDisplaySession(context) { status = it.message }
            .onFailure {
                Timber.e(it, "[LinuxDesktopScreen]: could not complete the session install")
                error = it.message
                return@LaunchedEffect
            }
        status = null

        LinuxSessions.open(
            context = context,
            key = key,
            label = label ?: context.getString(R.string.linux_session_notification_title),
            argv = argv,
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            densityDpi = metrics.densityDpi,
        )
            .onSuccess { opened ->
                withContext(Dispatchers.IO) { opened.setDpi(metrics.densityDpi) }
                LinuxSessions.attach(key)
                session = opened
            }
            .onFailure { error = it.message }
    }

    // Keyed on the session key rather than on the session, since assigning the session is itself
    // a key change and would tear down what had just been attached.
    DisposableEffect(key) {
        onDispose { LinuxSessions.detach(context, key) }
    }

    Box(
        // Without this the desktop is drawn under the window caption and the status bar,
        // which hides the top rows -- where a terminal puts its first line.
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing),
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
                        // The application in the session exited, which no lifetime mode is a
                        // reason to keep an empty X server for: "keep it running" is about
                        // closing the window, not about the program ending.
                        LinuxSessions.stop(context, key)
                        onExit()
                    }.apply { requestFocus() }
                },
                onRelease = { active.releaseView(it) },
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
