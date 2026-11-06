package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.composition.ActiveContextState
import io.github.alexanderwilhelmsenberg.modular.composition.ContextCapabilityBinding
import io.github.alexanderwilhelmsenberg.modular.composition.LauncherContext
import io.github.alexanderwilhelmsenberg.modular.composition.ModuleInstance
import io.github.alexanderwilhelmsenberg.modular.moduleapi.CapabilityId
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleRecord
import java.util.UUID
import kotlinx.coroutines.launch

/** Editors for saved provider instances and launcher context configurations. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompositionManagement(
    instances: List<ModuleInstance>,
    contexts: List<LauncherContext>,
    modules: List<ModuleRecord>,
    activeContext: ActiveContextState,
    onSaveInstance: suspend (ModuleInstance) -> Result<Unit>,
    onDeleteInstance: suspend (String) -> Result<Unit>,
    onSaveContext: suspend (LauncherContext) -> Result<Unit>,
    onDeleteContext: suspend (String) -> Result<Unit>,
    onActivateContext: (String) -> Unit,
    onDeactivateContext: () -> Unit,
) {
    var instanceEditor by remember { mutableStateOf<ModuleInstance?>(null) }
    var contextEditor by remember { mutableStateOf<LauncherContext?>(null) }
    var deleteInstance by remember { mutableStateOf<ModuleInstance?>(null) }
    var deleteContext by remember { mutableStateOf<LauncherContext?>(null) }
    val capabilities = remember(modules, contexts) {
        (modules.flatMap { record ->
            record.capabilities.mapNotNull(CapabilityId::parse)
        } + contexts.flatMap { context -> context.bindings.map { it.capability } })
            .distinct().sortedBy { it.wireValue }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Saved configurations", style = MaterialTheme.typography.titleLarge)
        Text(
            "Save reusable add-on configurations. The built-in Home remains available.",
            style = MaterialTheme.typography.bodyMedium,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                instanceEditor = ModuleInstance(UUID.randomUUID().toString(), "", "", "{}")
            }) { Text("Add instance") }
            OutlinedButton(onClick = {
                contextEditor = LauncherContext(UUID.randomUUID().toString(), "")
            }) { Text("Add context") }
        }

        if (activeContext.context != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Active context", style = MaterialTheme.typography.titleMedium)
                    Text(activeContext.context.name, style = MaterialTheme.typography.bodyLarge)
                    if (activeContext.problems.isNotEmpty()) {
                        Text(
                            activeContext.problems.joinToString("\n") {
                                "${it.capability.wireValue}: ${it.detail}"
                            },
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedButton(onClick = onDeactivateContext) { Text("Deactivate") }
                }
            }
        }

        if (contexts.isEmpty()) {
            Text("No contexts saved yet.", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text("Contexts", style = MaterialTheme.typography.titleMedium)
            contexts.forEach { context ->
                val isActive = activeContext.context?.id == context.id
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(context.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (context.bindings.isEmpty()) "No capability bindings" else
                                context.bindings.joinToString("\n") { binding ->
                                    val instanceName = instances.firstOrNull { it.id == binding.instanceId }?.name
                                        ?: "Missing instance"
                                    "${binding.capability.wireValue} → $instanceName"
                                },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (isActive) {
                                Text("Active", modifier = Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.primary)
                            } else {
                                OutlinedButton(onClick = { onActivateContext(context.id) }) { Text("Activate") }
                            }
                            TextButton(
                                modifier = Modifier.testTag("edit-context-${context.id}"),
                                onClick = { contextEditor = context },
                            ) { Text("Edit") }
                            TextButton(
                                modifier = Modifier.testTag("delete-context-${context.id}"),
                                onClick = { deleteContext = context },
                            ) { Text("Delete") }
                        }
                        if (isActive) Text(
                            "Deactivate this context before changing its bindings.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        if (instances.isNotEmpty()) {
            HorizontalDivider()
            Text("Provider instances", style = MaterialTheme.typography.titleMedium)
            instances.forEach { instance ->
                val referencedBy = contexts.filter { context ->
                    context.bindings.any { it.instanceId == instance.id }
                }.map { it.name }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(instance.name, style = MaterialTheme.typography.titleMedium)
                        Text("Provider: ${instance.providerId}", style = MaterialTheme.typography.bodyMedium)
                        Text("Configuration: ${instance.configurationJson}", style = MaterialTheme.typography.bodySmall)
                        if (referencedBy.isNotEmpty()) Text(
                            "Used by: ${referencedBy.joinToString()}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                modifier = Modifier.testTag("edit-instance-${instance.id}"),
                                onClick = { instanceEditor = instance },
                            ) { Text("Edit") }
                            TextButton(
                                modifier = Modifier.testTag("delete-instance-${instance.id}"),
                                onClick = { deleteInstance = instance },
                            ) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }

    instanceEditor?.let { initial ->
        InstanceEditorDialog(
            initial = initial,
            providerIds = (modules.mapNotNull { it.moduleId } + instances.map { it.providerId })
                .distinct().sorted(),
            onDismiss = { instanceEditor = null },
            onSave = onSaveInstance,
            onSaved = { instanceEditor = null },
        )
    }

    contextEditor?.let { initial ->
        ContextEditorDialog(
            initial = initial,
            capabilities = capabilities,
            instances = instances,
            bindingsLocked = activeContext.context?.id == initial.id,
            onDismiss = { contextEditor = null },
            onSave = onSaveContext,
            onSaved = { contextEditor = null },
        )
    }

    deleteInstance?.let { target ->
        ConfirmDeleteDialog("Delete instance “${target.name}”?", onDismiss = { deleteInstance = null }) {
            onDeleteInstance(target.id).onSuccess { deleteInstance = null }
        }
    }
    deleteContext?.let { target ->
        ConfirmDeleteDialog("Delete context “${target.name}”?", onDismiss = { deleteContext = null }) {
            onDeleteContext(target.id).onSuccess { deleteContext = null }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstanceEditorDialog(
    initial: ModuleInstance,
    providerIds: List<String>,
    onDismiss: () -> Unit,
    onSave: suspend (ModuleInstance) -> Result<Unit>,
    onSaved: () -> Unit,
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var providerId by remember(initial.id) { mutableStateOf(initial.providerId) }
    var json by remember(initial.id) { mutableStateOf(initial.configurationJson) }
    var error by remember(initial.id) { mutableStateOf<String?>(null) }
    var saving by remember(initial.id) { mutableStateOf(false) }
    var providerExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (initial.name.isBlank()) "New instance" else "Edit instance") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
                if (providerIds.isNotEmpty() && initial.providerId.isBlank()) {
                    ExposedDropdownMenuBox(
                        expanded = providerExpanded,
                        onExpandedChange = { if (!saving) providerExpanded = !providerExpanded },
                    ) {
                        OutlinedTextField(
                            providerId,
                            { providerId = it },
                            Modifier.fillMaxWidth().menuAnchor(),
                            label = { Text("Provider ID") },
                            supportingText = { Text("Choose a discovered module or enter its ID.") },
                            singleLine = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(providerExpanded) },
                        )
                        ExposedDropdownMenu(
                            expanded = providerExpanded,
                            onDismissRequest = { providerExpanded = false },
                        ) {
                            providerIds.forEach { id ->
                                DropdownMenuItem(
                                    text = { Text(id) },
                                    onClick = { providerId = id; providerExpanded = false },
                                )
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        providerId,
                        { if (initial.providerId.isBlank() || it == initial.providerId) providerId = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("Provider ID") },
                        supportingText = { Text(if (initial.providerId.isBlank()) "Enter a module provider ID." else "Provider identity cannot be changed for an existing instance.") },
                        singleLine = true,
                        readOnly = initial.providerId.isNotBlank(),
                    )
                }
                OutlinedTextField(
                    json, { json = it }, Modifier.fillMaxWidth(), label = { Text("Configuration JSON") },
                    supportingText = { Text("Use a JSON object, for example {}.") }, minLines = 4,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = !saving, onClick = {
                saving = true
                error = null
                scope.launch {
                    val result = runCatching { onSave(initial.copy(name = name, providerId = providerId, configurationJson = json)) }
                        .getOrElse { Result.failure(it) }
                    saving = false
                    result.onSuccess { onSaved() }.onFailure {
                        error = it.message ?: "Could not save instance."
                    }
                }
            }) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContextEditorDialog(
    initial: LauncherContext,
    capabilities: List<CapabilityId>,
    instances: List<ModuleInstance>,
    bindingsLocked: Boolean,
    onDismiss: () -> Unit,
    onSave: suspend (LauncherContext) -> Result<Unit>,
    onSaved: () -> Unit,
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    val bindings = remember(initial.id) { mutableStateMapOf<CapabilityId, String>().apply {
        initial.bindings.forEach { put(it.capability, it.instanceId) }
    } }
    var error by remember(initial.id) { mutableStateOf<String?>(null) }
    var saving by remember(initial.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (initial.name.isBlank()) "New context" else "Edit context") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 390.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Context name") }, singleLine = true)
                Text("Capability bindings", style = MaterialTheme.typography.titleSmall)
                if (bindingsLocked) Text("Bindings are locked while this context is active. Deactivate it to edit.", style = MaterialTheme.typography.bodySmall)
                if (capabilities.isEmpty()) Text("No capabilities are available yet.", style = MaterialTheme.typography.bodyMedium)
                capabilities.forEach { capability ->
                    val selected = bindings[capability]
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(capability.wireValue, style = MaterialTheme.typography.bodyMedium)
                        InstanceDropdown(
                            instances = instances,
                            selectedId = selected,
                            enabled = !bindingsLocked && !saving,
                            onSelect = { id -> if (id == null) bindings.remove(capability) else bindings[capability] = id },
                        )
                    }
                }
                if (instances.isEmpty() && capabilities.isNotEmpty()) Text("Add an instance before assigning bindings.", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = !saving, onClick = {
                saving = true
                error = null
                scope.launch {
                    val result = runCatching {
                        onSave(initial.copy(name = name, bindings = bindings.map { (capability, id) -> ContextCapabilityBinding(capability, id) }))
                    }.getOrElse { Result.failure(it) }
                    saving = false
                    result.onSuccess { onSaved() }.onFailure {
                        error = it.message ?: "Could not save context."
                    }
                }
            }) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstanceDropdown(
    instances: List<ModuleInstance>,
    selectedId: String?,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = instances.firstOrNull { it.id == selectedId }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = !expanded }) {
        OutlinedTextField(
            value = selected?.name ?: "Unassigned",
            onValueChange = {},
            modifier = Modifier.fillMaxWidth().menuAnchor().testTag("binding-${selectedId ?: "unassigned"}"),
            readOnly = true,
            enabled = enabled,
            label = { Text("Instance") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Unassigned") }, onClick = { onSelect(null); expanded = false })
            instances.forEach { instance ->
                DropdownMenuItem(text = { Text("${instance.name} · ${instance.providerId}") }, onClick = { onSelect(instance.id); expanded = false })
            }
        }
    }
}

@Composable
private fun ConfirmDeleteDialog(title: String, onDismiss: () -> Unit, onConfirm: suspend () -> Result<Unit>) {
    var error by remember(title) { mutableStateOf<String?>(null) }
    var saving by remember(title) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(title) },
        text = { Column { Text("This removes the saved configuration."); error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
        confirmButton = { TextButton(enabled = !saving, onClick = {
            saving = true
            scope.launch {
                val result = runCatching { onConfirm() }.getOrElse { Result.failure(it) }
                saving = false
                result.onFailure { error = it.message ?: "Could not delete configuration." }
            }
        }) { Text(if (saving) "Deleting…" else "Confirm delete") } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } },
    )
}
