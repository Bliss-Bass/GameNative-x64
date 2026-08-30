package app.gamenative.ui.screen.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.enums.PresentationPath
import app.gamenative.ui.component.settings.SettingsListDropdown
import app.gamenative.ui.theme.settingsTileColorsAlt
import app.gamenative.utils.HostCpu
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsSwitch

@Composable
fun SettingsGroupPerformance() {
    SettingsGroup {
        var powerControlDefaultEnabled by rememberSaveable { mutableStateOf(PrefManager.powerControlDefaultEnabled) }
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            state = powerControlDefaultEnabled,
            title = { Text(stringResource(R.string.settings_performance_power_control_title)) },
            subtitle = { Text(stringResource(R.string.settings_performance_power_control_subtitle)) },
            onCheckedChange = {
                powerControlDefaultEnabled = it
                PrefManager.powerControlDefaultEnabled = it
            },
        )

        // Only x86_64 guests reach the WSI paths this chooses between; on arm64 the graphics driver
        // setting already decides how frames are presented.
        if (HostCpu.current().isX86_64) {
            val paths = PresentationPath.entries
            var path by rememberSaveable { mutableStateOf(PrefManager.presentationPath) }
            SettingsListDropdown(
                colors = settingsTileColorsAlt(),
                title = { Text(stringResource(R.string.settings_performance_presentation_title)) },
                subtitle = { Text(stringResource(path.summaryRes)) },
                value = paths.indexOf(path),
                items = paths.map { stringResource(it.titleRes) },
                onItemSelected = { index ->
                    path = paths[index]
                    PrefManager.presentationPath = path
                },
            )
        }
    }
}
