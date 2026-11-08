package io.github.alexanderwilhelmsenberg.modular.appearance

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.ui.SettingsIcon
import io.github.alexanderwilhelmsenberg.modular.ui.SettingsRow
import io.github.alexanderwilhelmsenberg.modular.ui.SettingsSectionLabel
import kotlin.math.roundToInt

@Composable
fun AppearanceHomeControls(
    settings: HomeAppearanceSettings,
    onBackgroundSelected: (HomeBackground) -> Unit,
    onWallpaperDimmingSelected: (Int) -> Unit,
    onShowAppIconsChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var chooserError by remember { mutableStateOf<String?>(null) }
    var dimming by remember(settings.wallpaperDimming) {
        mutableFloatStateOf(settings.wallpaperDimming.toFloat())
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionLabel("Home background")
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            HomeBackground.entries.forEach { option ->
                SettingsRow(
                    title = option.label,
                    description = when (option) {
                        HomeBackground.THEME -> "Use the selected light, dark, or AMOLED theme"
                        HomeBackground.SYSTEM_WALLPAPER -> "Show your system wallpaper on Home"
                    },
                    icon = SettingsIcon.WALLPAPER,
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = settings.background == option,
                            onClick = { onBackgroundSelected(option) },
                            role = Role.RadioButton,
                        )
                        .testTag("home-background-${option.id}"),
                    trailing = { RadioButton(selected = settings.background == option, onClick = null) },
                )
            }
        }

        if (settings.background == HomeBackground.SYSTEM_WALLPAPER) {
            SettingsRow(
                title = "Wallpaper dimming",
                description = "${dimming.roundToInt()}%",
                icon = SettingsIcon.WALLPAPER,
            )
            Slider(
                value = dimming,
                onValueChange = { dimming = it },
                onValueChangeFinished = {
                    onWallpaperDimmingSelected(clampWallpaperDimming(dimming.roundToInt()))
                },
                valueRange = 0f..MAX_WALLPAPER_DIMMING.toFloat(),
                modifier = Modifier.fillMaxWidth().padding(start = 76.dp, end = 24.dp)
                    .testTag("wallpaper-dimming"),
            )
            SettingsRow(
                title = "Change system wallpaper",
                description = "Open your device's wallpaper chooser",
                icon = SettingsIcon.WALLPAPER,
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_SET_WALLPAPER))
                        chooserError = null
                    } catch (_: ActivityNotFoundException) {
                        chooserError = "Wallpaper chooser is unavailable on this device."
                    } catch (_: SecurityException) {
                        chooserError = "Wallpaper chooser is unavailable on this device."
                    }
                },
            )
            chooserError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }

        SettingsRow(
            title = "Show app icons",
            description = "Display app icons beside names on Home",
            icon = SettingsIcon.APPS,
            onClick = { onShowAppIconsChanged(!settings.showAppIcons) },
            trailing = {
                Switch(
                    checked = settings.showAppIcons,
                    onCheckedChange = onShowAppIconsChanged,
                    modifier = Modifier.testTag("show-app-icons"),
                )
            },
        )
    }
}
