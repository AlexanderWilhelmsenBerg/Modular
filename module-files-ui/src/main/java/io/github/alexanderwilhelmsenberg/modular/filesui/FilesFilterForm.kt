package io.github.alexanderwilhelmsenberg.modular.filesui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Filter data is local to the form until Apply. The caller sends its result to applyFilter. */
@Composable
fun FilesFilterForm(filter: JSONObject, onApply: (JSONObject) -> Unit, modifier: Modifier = Modifier) {
    val key = filter.toString()
    var query by remember(key) { mutableStateOf(filter.optString("query")) }
    var mimePrefix by remember(key) { mutableStateOf(filter.optString("mimePrefix")) }
    var includeSubfolders by remember(key) { mutableStateOf(filter.optBoolean("includeSubfolders", true)) }
    var allLocations by remember(key) { mutableStateOf(filter.optBoolean("allLocations", false)) }
    var after by remember(key) { mutableStateOf(dateText(filter, "modifiedAfter")) }
    var before by remember(key) { mutableStateOf(dateText(filter, "modifiedBefore")) }
    var minSize by remember(key) { mutableStateOf(numberText(filter, "minSizeBytes")) }
    var maxSize by remember(key) { mutableStateOf(numberText(filter, "maxSizeBytes")) }
    var error by remember(key) { mutableStateOf<String?>(null) }

    Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Search this location", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(query, { query = it.take(200) }, label = { Text("File or folder name") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(mimePrefix, { mimePrefix = it.take(100) },
            label = { Text("Type (MIME prefix, e.g. image/)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(includeSubfolders, onCheckedChange = { includeSubfolders = it })
            Text("Include subfolders", Modifier.padding(top = 12.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(allLocations, onCheckedChange = { allLocations = it })
            Text("All connected locations", Modifier.padding(top = 12.dp))
        }
        OutlinedTextField(after, { after = it.take(10) }, label = { Text("Modified on or after (YYYY-MM-DD)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(before, { before = it.take(10) }, label = { Text("Modified on or before (YYYY-MM-DD)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(minSize, { minSize = it.take(20) }, label = { Text("Min bytes") },
                singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(maxSize, { maxSize = it.take(20) }, label = { Text("Max bytes") },
                singleLine = true, modifier = Modifier.weight(1f))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                query = ""; mimePrefix = ""; after = ""; before = ""; minSize = ""; maxSize = ""
                includeSubfolders = true; allLocations = false; error = null
            }) { Text("Clear") }
            Button(onClick = {
                try {
                    val min = parseBytes(minSize)
                    val max = parseBytes(maxSize)
                    require(min == null || max == null || min <= max) { "Minimum size exceeds maximum" }
                    val zone = ZoneId.systemDefault()
                    val afterMillis = parseDate(after)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
                    val beforeMillis = parseDate(before)?.plusDays(1)?.atStartOfDay(zone)
                        ?.toInstant()?.toEpochMilli()?.minus(1)
                    require(afterMillis == null || beforeMillis == null || afterMillis <= beforeMillis) {
                        "Start date is after end date"
                    }
                    onApply(JSONObject().put("query", query.trim())
                        .put("includeSubfolders", includeSubfolders)
                        .put("allLocations", allLocations)
                        .put("mimePrefix", mimePrefix.trim().takeIf { it.isNotEmpty() })
                        .put("modifiedAfter", afterMillis)
                        .put("modifiedBefore", beforeMillis)
                        .put("minSizeBytes", min)
                        .put("maxSizeBytes", max))
                    error = null
                } catch (ex: Exception) {
                    error = ex.message ?: "Invalid filter"
                }
            }) { Text("Apply filters") }
        }
    }
}

private fun parseBytes(text: String): Long? {
    if (text.isBlank()) return null
    return text.trim().toLongOrNull()?.takeIf { it >= 0 }
        ?: throw IllegalArgumentException("Size must be a nonnegative whole number of bytes")
}

private fun parseDate(text: String): LocalDate? {
    if (text.isBlank()) return null
    return runCatching { LocalDate.parse(text.trim()) }.getOrElse {
        throw IllegalArgumentException("Use YYYY-MM-DD for dates")
    }
}

private fun dateText(filter: JSONObject, key: String): String {
    if (!filter.has(key) || filter.isNull(key)) return ""
    val millis = filter.optLong(key, Long.MIN_VALUE)
    return if (millis == Long.MIN_VALUE) "" else
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
}

private fun numberText(filter: JSONObject, key: String): String =
    if (!filter.has(key) || filter.isNull(key)) "" else filter.optLong(key).toString()
