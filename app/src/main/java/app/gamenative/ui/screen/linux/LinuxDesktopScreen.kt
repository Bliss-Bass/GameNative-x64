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
import app.gamenative.linux.LinuxDisplaySession
import app.gamenative.linux.rfb.RfbView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * A Linux graphical session, presented over RFB from an X server in the rootfs.
 *
 * @param argv optional program to launch into the session once it is up.
 */
@Composable
fun LinuxDesktopScreen(
    argv: String? = null,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val metrics = context.resources.displayMetrics

    var session by remember { mutableStateOf<LinuxDisplaySession?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        LinuxDisplaySession.start(context, metrics.widthPixels, metrics.heightPixels)
            .onSuccess { started ->
                withContext(Dispatchers.IO) {
                    started.setDpi(metrics.densityDpi)
                    argv?.let { started.run(it) }
                }
                session = started
            }
            .onFailure {
                Timber.e(it, "[LinuxDesktopScreen]: could not start the session")
                error = it.message
            }
    }

    // Keyed on Unit, not on the session: keying on it would tear the session down as soon
    // as it was assigned, since that assignment is itself a key change.
    DisposableEffect(Unit) {
        onDispose { session?.let { active -> Thread { active.stop() }.start() } }
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
            active == null -> Starting()
            else -> AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    RfbView(viewContext, active.port) { failure ->
                        if (failure != null) error = failure.message else onExit()
                    }.apply { requestFocus() }
                },
                onRelease = { it.disconnect() },
            )
        }
    }
}

@Composable
private fun Starting() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator()
        Text(text = stringResource(R.string.linux_desktop_starting), color = Color.White)
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
