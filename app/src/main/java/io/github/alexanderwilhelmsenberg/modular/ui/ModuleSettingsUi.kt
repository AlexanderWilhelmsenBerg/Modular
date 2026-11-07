package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.moduleapi.InstanceConfigurationField
import io.github.alexanderwilhelmsenberg.modular.moduleapi.ModuleDescriptorCodec
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleConnectionStatus
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleRecord
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Common module settings, built directly from the provider's validated descriptor. */
@Composable
fun ModuleSettingsDialog(
    module: ModuleRecord,
    configurationJson: String,
    isUsedForAppOrder: Boolean,
    onSave: suspend (String, Boolean) -> Result<Unit>,
    onDismiss: () -> Unit,
    onOpenAddonSettings: ((ModuleRecord) -> Unit)? = null,
    available: Boolean = module.status == ModuleConnectionStatus.CONNECTED,
    featuredSettingsContent: (@Composable () -> Unit)? = null,
) {
    val descriptor = module.descriptor
    val isSortModule = descriptor?.providedCapabilities?.any {
        it == StandardCapabilities.AppsSort.wireValue || it == StandardCapabilities.AppsFeatured.wireValue
    } ?: module.capabilities.any {
        it == StandardCapabilities.AppsSort.wireValue || it == StandardCapabilities.AppsFeatured.wireValue
    }
    val fields = descriptor?.configurationFields.orEmpty()
    val normalized = remember(descriptor, configurationJson) {
        descriptor?.let { runCatching { ModuleDescriptorCodec.normalizeConfiguration(it, configurationJson) }.getOrNull() }
    }
    val initialValues = remember(normalized) {
        (normalized?.let { Json.parseToJsonElement(it) as? JsonObject }) ?: JsonObject(emptyMap())
    }
    val values = remember(module.component, descriptor, normalized) {
        mutableStateMapOf<String, String>().apply {
            fields.forEach { field -> put(field.key, (initialValues[field.key] as? JsonPrimitive)?.content ?: field.defaultValue.content) }
        }
    }
    var error by remember(module.component) { mutableStateOf<String?>(null) }
    var showValidation by remember(module.component) { mutableStateOf(false) }
    var saving by remember(module.component) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val editable = descriptor != null && available && module.enabled &&
        module.status == ModuleConnectionStatus.CONNECTED && !saving
    val fieldErrors = fields.associate { it.key to fieldError(it, values[it.key].orEmpty()) }
    val canSave = editable

    fun submit(useForAppOrder: Boolean) {
        showValidation = true
        error = null
        if (!canSave || descriptor == null || fieldErrors.values.any { it != null }) return
        val raw = JsonObject(fields.associate { field ->
            val rawValue = values[field.key].orEmpty()
            field.key to when (field.type) {
                "boolean" -> JsonPrimitive(rawValue.toBoolean())
                "integer" -> JsonPrimitive(rawValue.toLong())
                else -> JsonPrimitive(rawValue)
            }
        }).toString()
        val validated = runCatching { ModuleDescriptorCodec.normalizeConfiguration(descriptor, raw) }
            .getOrElse {
                error = "Check the settings and try again."
                return
            }
        saving = true
        scope.launch {
            val result = runCatching { onSave(validated, useForAppOrder) }.getOrElse { Result.failure(it) }
            saving = false
            result.onSuccess { onDismiss() }.onFailure {
                error = it.message ?: "Could not save settings."
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("${module.label} settings") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                descriptor?.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                val statusMessage = when {
                    !module.enabled || module.status == ModuleConnectionStatus.DISABLED ->
                        "This module is disabled. Enable it to change settings."
                    module.status == ModuleConnectionStatus.INCOMPATIBLE ->
                        "This module is incompatible with this version of Modular. Settings are unavailable."
                    module.status == ModuleConnectionStatus.CONNECTING ->
                        "This module is connecting. Settings will be available when it connects."
                    !available ->
                        "This module is offline. Reconnect it to change settings."
                    else -> null
                }
                statusMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (descriptor == null) {
                    Text(
                        module.descriptorError?.let { "Settings are unavailable: $it" }
                            ?: "This module does not provide settings to Modular.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    if (normalized == null) Text(
                        "Saved settings could not be read. Defaults are shown; saving will replace them.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (fields.isEmpty()) Text("This module has no settings to adjust.", style = MaterialTheme.typography.bodyMedium)
                    fields.forEach { field ->
                        ModuleSettingField(
                            field = field,
                            value = values[field.key].orEmpty(),
                            enabled = editable,
                            error = fieldErrors[field.key]?.takeIf { showValidation },
                            onValueChange = { values[field.key] = it; error = null },
                        )
                    }
                    if (isSortModule) Text(
                        if (isUsedForAppOrder) "This module currently contributes apps on Home."
                        else "Home currently uses another app order.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (isUsedForAppOrder && editable) featuredSettingsContent?.invoke()
                }
                if (descriptor?.managementActivity != null && onOpenAddonSettings != null) {
                    OutlinedButton(
                        enabled = !saving,
                        onClick = { onOpenAddonSettings(module) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Permissions and settings") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (descriptor != null) Button(
                onClick = { submit(isSortModule) },
                enabled = canSave,
                modifier = Modifier.testTag("module-settings-save"),
            ) { Text(if (saving) "Saving…" else if (isSortModule && !isUsedForAppOrder) "Use on Home" else "Save settings") }
        },
        dismissButton = {
            Column {
                if (isSortModule && isUsedForAppOrder) TextButton(
                    enabled = canSave,
                    onClick = { submit(false) },
                ) { Text("Use alphabetical order") }
                TextButton(enabled = !saving, onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

@Composable
private fun ModuleSettingField(
    field: InstanceConfigurationField,
    value: String,
    enabled: Boolean,
    error: String?,
    onValueChange: (String) -> Unit,
) {
    when {
        field.type == "boolean" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(field.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = value == "true",
                enabled = enabled,
                onCheckedChange = { onValueChange(it.toString()) },
                modifier = Modifier.semantics { contentDescription = field.title }.testTag("module-setting-${field.key}"),
            )
        }
        field.enumValues.isNotEmpty() -> {
            var expanded by remember(field.key) { mutableStateOf(false) }
            Column {
                Text(field.title, style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(
                    onClick = { expanded = true },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().testTag("module-setting-${field.key}"),
                ) { Text(value) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    field.enumValues.forEach { option ->
                        DropdownMenuItem(text = { Text(option.content) }, onClick = {
                            onValueChange(option.content)
                            expanded = false
                        })
                    }
                }
            }
        }
        else -> OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().testTag("module-setting-${field.key}"),
            enabled = enabled,
            label = { Text(field.title) },
            singleLine = true,
            isError = error != null,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (field.type == "integer" && (field.min ?: 0) >= 0) KeyboardType.Number
                else KeyboardType.Text,
            ),
            supportingText = {
                val hint = error ?: when (field.type) {
                    "integer" -> rangeHint(field)
                    else -> null
                }
                hint?.let { Text(it) }
            },
        )
    }
}

private fun rangeHint(field: InstanceConfigurationField): String? = when {
    field.min != null && field.max != null -> "${field.min}–${field.max}"
    field.min != null -> "At least ${field.min}"
    field.max != null -> "At most ${field.max}"
    else -> null
}

private fun fieldError(field: InstanceConfigurationField, value: String): String? = when (field.type) {
    "integer" -> {
        val number = value.toLongOrNull()
        when {
            number == null -> "Enter a whole number."
            field.min?.let { number < it } == true -> "Enter ${rangeHint(field)}."
            field.max?.let { number > it } == true -> "Enter ${rangeHint(field)}."
            else -> null
        }
    }
    "string" -> when {
        field.minLength?.let { value.length < it } == true -> "Enter at least ${field.minLength} characters."
        field.maxLength?.let { value.length > it } == true -> "Enter at most ${field.maxLength} characters."
        else -> null
    }
    "boolean" -> if (value == "true" || value == "false") null else "Choose on or off."
    else -> "This setting is unavailable."
}
