package app.gamenative.ui.screen.terminal

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import app.gamenative.linux.LinuxShellSession
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.pluviaTopSafeAreaPadding
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * A shell inside the Linux userland, so that packages can be installed with apt.
 *
 * The terminal emulator and view are vendored from Termux under com.termux; this screen
 * is the glue that points them at a PRoot bash and puts them in the app's shell.
 */
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var installing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<LinuxRootfs.Progress?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var installed by remember { mutableStateOf(LinuxRootfs.isInstalled(context)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pluviaTopSafeAreaPadding()
            .imePadding(),
    ) {
        TerminalHeader(onBack = onBack)

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                !LinuxRootfs.isSupported() -> Message(stringResource(R.string.terminal_unsupported_abi))

                installing -> InstallProgress(progress)

                error != null -> Message(error!!)

                installed -> Terminal(onSessionEnded = onBack)

                else -> InstallPrompt(
                    onInstall = {
                        installing = true
                        error = null
                        scope.launch {
                            LinuxRootfs.install(context) { progress = it }
                                .onSuccess { installed = true }
                                .onFailure { error = it.message ?: it.toString() }
                            installing = false
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun Terminal(onSessionEnded: () -> Unit) {
    val context = LocalContext.current
    // Held across recompositions: recreating it would restart the shell and lose the
    // scrollback, and a long apt run with it.
    val session = remember { mutableStateOf<TerminalSession?>(null) }
    val terminalView = remember { mutableStateOf<TerminalView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            session.value?.finishIfRunning()
            session.value = null
            terminalView.value = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                TerminalView(viewContext, null).apply {
                    setTextSize((14 * viewContext.resources.displayMetrics.density).toInt())
                    keepScreenOn = true
                    // Without this the view never becomes the IME target: keystrokes fall
                    // through to Compose, where Enter activates whatever is focused and
                    // navigates away from the screen, killing the shell.
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setTerminalViewClient(GameNativeTerminalViewClient(this))

                    val client = GameNativeTerminalSessionClient(
                        view = this,
                        onFinished = onSessionEnded,
                    )
                    val newSession = LinuxShellSession.create(viewContext, client)
                    session.value = newSession
                    terminalView.value = this
                    attachSession(newSession)
                    requestFocus()
                }
            },
        )

        // Physical keyboards are the exception on a tablet, and the view only raises the
        // IME on its own when it already has focus.
        IconButton(
            onClick = { terminalView.value?.let(::showKeyboard) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Keyboard,
                contentDescription = stringResource(R.string.terminal_show_keyboard),
                tint = PluviaTheme.colors.accentCyan,
            )
        }
    }
}

private fun showKeyboard(view: TerminalView) {
    view.requestFocus()
    val ime = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    ime?.showSoftInput(view, 0)
}

@Composable
private fun TerminalHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.back),
                tint = Color.White.copy(alpha = 0.8f),
            )
        }
        Text(
            text = stringResource(R.string.terminal_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun InstallPrompt(onInstall: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.terminal_install_rationale),
            style = MaterialTheme.typography.bodyMedium,
            color = PluviaTheme.colors.textMuted,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onInstall, modifier = Modifier.padding(top = 24.dp)) {
            Text(stringResource(R.string.terminal_install))
        }
    }
}

@Composable
private fun InstallProgress(progress: LinuxRootfs.Progress?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val fraction = progress?.fraction ?: -1f
        if (fraction >= 0f) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            CircularProgressIndicator()
        }
        Text(
            text = progress?.message ?: stringResource(R.string.terminal_installing),
            style = MaterialTheme.typography.bodyMedium,
            color = PluviaTheme.colors.textMuted,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun Message(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = PluviaTheme.colors.textMuted,
            textAlign = TextAlign.Center,
        )
    }
}

/** Routes the session's output and lifecycle back into the view. */
private class GameNativeTerminalSessionClient(
    private val view: TerminalView,
    private val onFinished: () -> Unit,
) : TerminalSessionClient {

    override fun onTextChanged(changedSession: TerminalSession) = view.onScreenUpdated()

    override fun onTitleChanged(changedSession: TerminalSession) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession) {
        Timber.i("[Terminal]: shell exited with %d", finishedSession.exitStatus)
        onFinished()
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = view.context.getSystemService(android.content.ClipboardManager::class.java)
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clipboard = view.context.getSystemService(android.content.ClipboardManager::class.java)
        val text = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(view.context)?.toString()
        if (!text.isNullOrEmpty()) session?.write(text)
    }

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) = Unit

    override fun onTerminalCursorStateChange(state: Boolean) = Unit

    override fun getTerminalCursorStyle(): Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK

    override fun logError(tag: String, message: String) = Timber.e("[%s]: %s", tag, message)

    override fun logWarn(tag: String, message: String) = Timber.w("[%s]: %s", tag, message)

    override fun logInfo(tag: String, message: String) = Timber.i("[%s]: %s", tag, message)

    override fun logDebug(tag: String, message: String) = Timber.d("[%s]: %s", tag, message)

    override fun logVerbose(tag: String, message: String) = Timber.v("[%s]: %s", tag, message)

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) =
        Timber.e(e, "[%s]: %s", tag, message)

    override fun logStackTrace(tag: String, e: Exception) = Timber.e(e, "[%s]", tag)
}

/** Input handling. Deliberately plain: no extra keys row, no gesture shortcuts. */
private class GameNativeTerminalViewClient(private val view: TerminalView) : TerminalViewClient {

    override fun onScale(scale: Float): Float = 1f

    override fun onSingleTapUp(e: MotionEvent?) = showKeyboard(view)

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

    // Ctrl+Space is how bash's set-mark is reached, and some IMEs swallow it otherwise.
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = true

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false

    override fun onLongPress(event: MotionEvent?): Boolean = false

    override fun readControlKey(): Boolean = false

    override fun readAltKey(): Boolean = false

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false

    override fun onEmulatorSet() = Unit

    override fun logError(tag: String, message: String) = Timber.e("[%s]: %s", tag, message)

    override fun logWarn(tag: String, message: String) = Timber.w("[%s]: %s", tag, message)

    override fun logInfo(tag: String, message: String) = Timber.i("[%s]: %s", tag, message)

    override fun logDebug(tag: String, message: String) = Timber.d("[%s]: %s", tag, message)

    override fun logVerbose(tag: String, message: String) = Timber.v("[%s]: %s", tag, message)

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) =
        Timber.e(e, "[%s]: %s", tag, message)

    override fun logStackTrace(tag: String, e: Exception) = Timber.e(e, "[%s]", tag)
}
