package io.github.alexanderwilhelmsenberg.modular.appearance

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
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
        Text("Home background", style = MaterialTheme.typography.titleMedium)
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            HomeBackground.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = settings.background == option,
                            onClick = { onBackgroundSelected(option) },
                            role = Role.RadioButton,
                        )
                        .testTag("home-background-${option.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.background == option, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(option.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        if (settings.background == HomeBackground.SYSTEM_WALLPAPER) {
            Text("Wallpaper dimming: ${dimming.roundToInt()}%")
            Slider(
                value = dimming,
                onValueChange = { dimming = it },
                onValueChangeFinished = {
                    onWallpaperDimmingSelected(clampWallpaperDimming(dimming.roundToInt()))
                },
                valueRange = 0f..MAX_WALLPAPER_DIMMING.toFloat(),
                modifier = Modifier.testTag("wallpaper-dimming"),
            )
            TextButton(onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_SET_WALLPAPER))
                    chooserError = null
                } catch (_: ActivityNotFoundException) {
                    chooserError = "Wallpaper chooser is unavailable on this device."
                } catch (_: SecurityException) {
                    chooserError = "Wallpaper chooser is unavailable on this device."
                }
            }) {
                Text("Change system wallpaper")
            }
            chooserError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }

        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Show app icons", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = settings.showAppIcons,
                onCheckedChange = onShowAppIconsChanged,
                modifier = Modifier.testTag("show-app-icons"),
            )
        }
    }
}
