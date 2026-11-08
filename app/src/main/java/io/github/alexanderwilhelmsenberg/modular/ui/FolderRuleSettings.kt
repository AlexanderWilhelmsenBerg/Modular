package io.github.alexanderwilhelmsenberg.modular.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.alexanderwilhelmsenberg.modular.organization.AndroidForegroundLocationSource
import io.github.alexanderwilhelmsenberg.modular.organization.ConditionOutcome
import io.github.alexanderwilhelmsenberg.modular.organization.FolderCondition
import io.github.alexanderwilhelmsenberg.modular.organization.FolderDefinition
import io.github.alexanderwilhelmsenberg.modular.organization.FolderMatchMode
import io.github.alexanderwilhelmsenberg.modular.organization.FolderVisibilityMode
import io.github.alexanderwilhelmsenberg.modular.organization.FolderVisibilityStatus
import io.github.alexanderwilhelmsenberg.modular.organization.HeadsetKind
import io.github.alexanderwilhelmsenberg.modular.organization.SavedArea
import io.github.alexanderwilhelmsenberg.modular.organization.UnknownFallback
import io.github.alexanderwilhelmsenberg.modular.organization.WeeklyInterval
import kotlinx.coroutines.launch
import java.util.UUID

/** Renders a folder's staged rule draft. Caller commits rules and folder together on Save. */
@Composable
fun FolderRuleSettings(
    folder: FolderDefinition,
    conditions: List<FolderCondition>,
    status: FolderVisibilityStatus?,
    onFolderChange: (FolderDefinition) -> Unit,
    onConditionsChange: (List<FolderCondition>) -> Unit,
    onRequestLocationPermission: () -> Unit = {},
    onRequestWifiPermission: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun change(condition: FolderCondition) = onConditionsChange(conditions.map {
        if (it.id == condition.id) condition else it
    })
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Visibility", style = MaterialTheme.typography.titleMedium)
        listOf(
            FolderVisibilityMode.ALWAYS to "Always show",
            FolderVisibilityMode.SMART to "Smart visibility",
            FolderVisibilityMode.HIDDEN to "Manually hide",
        ).forEach { (mode, label) ->
            FilterChip(folder.visibilityMode == mode, { onFolderChange(folder.copy(visibilityMode = mode)) },
                label = { Text(label) })
        }
        if (folder.visibilityMode == FolderVisibilityMode.SMART || conditions.isNotEmpty()) {
            Text("Rules", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(folder.matchMode == FolderMatchMode.ANY,
                    { onFolderChange(folder.copy(matchMode = FolderMatchMode.ANY)) }, label = { Text("Match any") })
                FilterChip(folder.matchMode == FolderMatchMode.ALL,
                    { onFolderChange(folder.copy(matchMode = FolderMatchMode.ALL)) }, label = { Text("Match all") })
            }
            Text("If a signal cannot be checked:")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(folder.unknownFallback == UnknownFallback.SHOW,
                    { onFolderChange(folder.copy(unknownFallback = UnknownFallback.SHOW)) }, label = { Text("Show") })
                FilterChip(folder.unknownFallback == UnknownFallback.HIDE,
                    { onFolderChange(folder.copy(unknownFallback = UnknownFallback.HIDE)) }, label = { Text("Hide until known") })
            }
            Text("Show can display the shortcut when location or Wi-Fi is unknown. Hide keeps it in Manage folders. These rules are for convenience, not security.",
                style = MaterialTheme.typography.bodySmall)
            conditions.forEach { condition ->
                val current = status?.conditions?.get(condition.id)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(when (condition) {
                                is FolderCondition.Headset -> "Headset connected"
                                is FolderCondition.Schedule -> "Time schedule"
                                is FolderCondition.Wifi -> "Connected Wi-Fi"
                                is FolderCondition.Area -> "Inside saved area"
                            }, style = MaterialTheme.typography.titleSmall)
                            TextButton({ onConditionsChange(conditions.filterNot { it.id == condition.id }) }) { Text("Remove") }
                        }
                        Row {
                            Checkbox(condition.enabled, onCheckedChange = { checked -> change(when (condition) {
                                is FolderCondition.Headset -> condition.copy(enabled = checked)
                                is FolderCondition.Schedule -> condition.copy(enabled = checked)
                                is FolderCondition.Wifi -> condition.copy(enabled = checked)
                                is FolderCondition.Area -> condition.copy(enabled = checked)
                            }) })
                            Text("Enabled", Modifier.padding(top = 12.dp))
                        }
                        if (current != null) Text("Current: ${current.outcome.display()} · ${current.reason.display()} · ${current.evaluatedAt}",
                            style = MaterialTheme.typography.bodySmall)
                        when (condition) {
                            is FolderCondition.Headset -> HeadsetFields(condition, ::change)
                            is FolderCondition.Schedule -> ScheduleFields(condition, ::change)
                            is FolderCondition.Wifi -> {
                                WifiFields(condition, ::change)
                                Text("Uses the current connection only. An SSID is not a secure or unique place identity. Android may require precise location and Location service to read it.",
                                    style = MaterialTheme.typography.bodySmall)
                                TextButton(onRequestWifiPermission) { Text("Allow Wi-Fi identity access") }
                            }
                            is FolderCondition.Area -> {
                                AreaFields(condition, ::change, onUseMyLocation = { area ->
                                    val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION).any {
                                        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                                    }
                                    if (!granted) onRequestLocationPermission()
                                    else scope.launch {
                                        val fix = AndroidForegroundLocationSource(context).currentFix()
                                        if (fix != null) change(condition.copy(areas = condition.areas.map {
                                            if (it.id == area.id) it.copy(latitude = fix.latitude, longitude = fix.longitude) else it
                                        }))
                                    }
                                })
                                Text("Area checks need foreground location. An inaccurate fix near the boundary stays unknown.",
                                    style = MaterialTheme.typography.bodySmall)
                                TextButton(onRequestLocationPermission) { Text("Allow location access") }
                            }
                        }
                    }
                }
            }
            if (folder.visibilityMode == FolderVisibilityMode.SMART && conditions.none { it.enabled })
                Text("Add and enable at least one rule before saving Smart visibility.",
                    color = MaterialTheme.colorScheme.error)
            if (conditions.size < 16) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton({ onConditionsChange(conditions + FolderCondition.Headset(UUID.randomUUID().toString())) }) { Text("+ Headset") }
                    TextButton({ onConditionsChange(conditions + FolderCondition.Schedule(UUID.randomUUID().toString(), intervals = listOf(WeeklyInterval()))) }) { Text("+ Time") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton({ onConditionsChange(conditions + FolderCondition.Wifi(UUID.randomUUID().toString())) }) { Text("+ Wi-Fi") }
                    TextButton({ onConditionsChange(conditions + FolderCondition.Area(UUID.randomUUID().toString())) }) { Text("+ Area") }
                }
            }
        }
    }
}

private fun ConditionOutcome.display() = when (this) {
    ConditionOutcome.MATCH -> "Match"; ConditionOutcome.NO_MATCH -> "No match"; ConditionOutcome.UNKNOWN -> "Unknown"
}
private fun io.github.alexanderwilhelmsenberg.modular.organization.ConditionReason.display() =
    name.lowercase().replace('_', ' ')

@Composable private fun HeadsetFields(rule: FolderCondition.Headset, onChange: (FolderCondition) -> Unit) {
    Text("Connected output type")
    HeadsetKind.entries.forEach { kind ->
        FilterChip(rule.kind == kind, { onChange(rule.copy(kind = kind)) }, label = { Text(kind.name.lowercase().replaceFirstChar(Char::uppercase)) })
    }
}

@Composable private fun ScheduleFields(rule: FolderCondition.Schedule, onChange: (FolderCondition) -> Unit) {
    rule.intervals.forEachIndexed { index, interval ->
        var start by remember(rule.id, index) { mutableStateOf(minutesText(interval.startMinute)) }
        var end by remember(rule.id, index) { mutableStateOf(minutesText(interval.endMinute)) }
        fun update(value: WeeklyInterval) = onChange(rule.copy(intervals = rule.intervals.toMutableList().apply { this[index] = value }))
        Text("Interval ${index + 1}")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(start, { value -> start = value; parseMinutes(value)?.let { update(interval.copy(startMinute = it)) } },
                label = { Text("Start HH:mm") }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedTextField(end, { value -> end = value; parseMinutes(value)?.let { update(interval.copy(endMinute = it)) } },
                label = { Text("End HH:mm") }, modifier = Modifier.weight(1f), singleLine = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            listOf("M", "Tu", "W", "Th", "F", "Sa", "Su").forEachIndexed { dayIndex, label ->
                val day = dayIndex + 1
                FilterChip(day in interval.weekdays, {
                    update(interval.copy(weekdays = if (day in interval.weekdays) interval.weekdays - day else interval.weekdays + day))
                }, label = { Text(label) })
            }
        }
        TextButton({ onChange(rule.copy(intervals = rule.intervals.filterIndexed { i, _ -> i != index })) }) { Text("Remove interval") }
    }
    if (rule.intervals.size < 16) TextButton({ onChange(rule.copy(intervals = rule.intervals + WeeklyInterval())) }) { Text("Add interval") }
    Text("Overnight times continue into the next morning. Times use the device's local timezone.",
        style = MaterialTheme.typography.bodySmall)
}
private fun minutesText(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)
private fun parseMinutes(value: String): Int? {
    val match = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(value) ?: return null
    val hour = match.groupValues[1].toIntOrNull() ?: return null
    val minute = match.groupValues[2].toIntOrNull() ?: return null
    return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
}

@Composable private fun WifiFields(rule: FolderCondition.Wifi, onChange: (FolderCondition) -> Unit) {
    var input by remember(rule.id) { mutableStateOf(rule.ssids.joinToString("\n")) }
    OutlinedTextField(input, { text ->
        input = text
        val names = text.lines().filter(String::isNotBlank).distinct()
        if (names.size <= 16 && names.all { it.length <= 64 }) onChange(rule.copy(ssids = names))
    }, label = { Text("Network names, one per line") }, modifier = Modifier.fillMaxWidth(), minLines = 2,
        isError = input.lines().filter(String::isNotBlank).distinct().let { names ->
            names.size > 16 || names.any { it.length > 64 }
        })
    if (input.lines().filter(String::isNotBlank).distinct().let { it.size > 16 || it.any { name -> name.length > 64 } })
        Text("Use at most 16 network names of 64 characters each.", color = MaterialTheme.colorScheme.error)
}

@Composable private fun AreaFields(rule: FolderCondition.Area, onChange: (FolderCondition) -> Unit,
    onUseMyLocation: (SavedArea) -> Unit) {
    rule.areas.forEach { area ->
        fun update(updated: SavedArea) = onChange(rule.copy(areas = rule.areas.map { if (it.id == area.id) updated else it }))
        OutlinedTextField(area.name, { update(area.copy(name = it.take(80))) },
            label = { Text("Area name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        SavedAreaMapEditor(area, ::update, Modifier.fillMaxWidth())
        TextButton({ onUseMyLocation(area) }) { Text("Use my location") }
        TextButton({ onChange(rule.copy(areas = rule.areas.filterNot { it.id == area.id })) }) { Text("Remove area") }
    }
    if (rule.areas.size < 16) TextButton({
        onChange(rule.copy(areas = rule.areas + SavedArea(UUID.randomUUID().toString(),
            "New area", 0.0, 0.0, 500.0)))
    }) { Text("Add area") }
    Text("Map tiles load only while this editor is open. If tiles cannot load, latitude, longitude and radius still work.",
        style = MaterialTheme.typography.bodySmall)
}
