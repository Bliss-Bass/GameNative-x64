package app.gamenative.ui.screen.linux

import android.graphics.BitmapFactory
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.linux.LinuxAppScanner
import app.gamenative.linux.LinuxRootfs
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.pluviaTopSafeAreaPadding
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    onLaunch: (argv: String) -> Unit,
) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }

    // Reading a few dozen small files, off the main thread. Rescanned on request because
    // apt runs in the terminal, out of sight of this screen.
    val apps by produceState<List<LinuxAppScanner.LinuxApp>?>(initialValue = null, refreshKey) {
        value = withContext(Dispatchers.IO) { LinuxAppScanner.scan(context) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pluviaTopSafeAreaPadding(),
    ) {
        val onRefresh: (() -> Unit)? =
            if (LinuxRootfs.isInstalled(context)) {
                { refreshKey++ }
            } else {
                null
            }
        Header(onBack = onBack, onRefresh = onRefresh)

        Box(modifier = Modifier.fillMaxSize()) {
            val found = apps
            when {
                !LinuxRootfs.isSupported() -> Message(stringResource(R.string.linux_apps_unsupported))

                !LinuxRootfs.isInstalled(context) -> Message(
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
                        AppRow(app = app, onClick = { onLaunch(app.launchArgv) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: LinuxAppScanner.LinuxApp, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(iconPath = app.iconPath)

        Column(modifier = Modifier.padding(start = 16.dp)) {
            Text(
                text = app.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The comment is the entry's own one-line description, which is more useful
            // than the command; the terminal note is not obvious from either.
            val subtitle = app.comment
                ?: stringResource(R.string.linux_apps_terminal_app).takeIf { app.terminal }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = PluviaTheme.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The entry's icon, or a stand-in.
 *
 * Only PNG is decoded. Icon themes also use SVG, and pixmaps still use XPM, neither of
 * which Android reads, and pulling in a renderer for a list icon is not worth it.
 */
@Composable
private fun AppIcon(iconPath: String?) {
    val context = LocalContext.current
    val rootfs = remember { LinuxRootfs.rootfsDir(context) }

    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, iconPath) {
        value = withContext(Dispatchers.IO) {
            val png = iconPath?.takeIf { it.endsWith(".png", ignoreCase = true) } ?: return@withContext null
            runCatching {
                BitmapFactory.decodeFile(File(rootfs, png.removePrefix("/")).absolutePath)?.asImageBitmap()
            }.getOrNull()
        }
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
private fun Header(onBack: () -> Unit, onRefresh: (() -> Unit)?) {
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
        if (onRefresh != null) {
            IconButton(onClick = onRefresh, modifier = Modifier.size(44.dp)) {
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
