package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.apps.AppProfile
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityProviderKind
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityRoute
import io.github.alexanderwilhelmsenberg.modular.composition.LauncherContext
import io.github.alexanderwilhelmsenberg.modular.composition.ModuleInstance
import io.github.alexanderwilhelmsenberg.modular.moduleapi.CapabilityId
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleRecord
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleConnectionStatus
import io.github.alexanderwilhelmsenberg.modular.security.ModuleSecurityStatus

@Composable
fun LauncherCompositionPanel(
    routes: List<CapabilityRoute>,
    profiles: List<AppProfile>,
    busy: Boolean,
    error: String?,
    onCreate: (String, String, String, String, Long?) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("Personal") }
    var home by rememberSaveable { mutableStateOf("") }
    var drawer by rememberSaveable { mutableStateOf("") }
    var sorter by rememberSaveable { mutableStateOf("") }
    var profile by rememberSaveable { mutableStateOf<Long?>(null) }
    fun providers(capability: CapabilityId) = routes.filter {
        it.providerKind == CapabilityProviderKind.MODULE && it.capability == capability
    }.groupBy { it.providerId }.filterValues { it.size == 1 }.keys.sorted()
    val homes = providers(StandardCapabilities.SurfaceHome)
    val drawers = providers(StandardCapabilities.SurfaceDrawer)
    val sorters = providers(StandardCapabilities.AppsSort)
    val selectedHome = home.takeIf { it in homes } ?: homes.singleOrNull().orEmpty()
    val selectedDrawer = drawer.takeIf { it in drawers } ?: drawers.singleOrNull().orEmpty()
    val selectedSorter = sorter.takeIf { it in sorters } ?: sorters.singleOrNull().orEmpty()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Launcher setup", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(name, { name = it }, label = { Text("Context name") }, enabled = !busy)
            ProviderChoices("Home", homes, selectedHome, !busy) { home = it }
            ProviderChoices("Drawer", drawers, selectedDrawer, !busy) { drawer = it }
            ProviderChoices("Sort", sorters, selectedSorter, !busy) { sorter = it }
            Text("Drawer profile")
            FilterChip(profile == null, { profile = null }, label = { Text("All profiles") }, enabled = !busy)
            profiles.forEach { item ->
                FilterChip(profile == item.serialNumber, { profile = item.serialNumber },
                    label = { Text("Profile ${item.serialNumber}" + if (item.quietModeEnabled) " (paused)" else "") }, enabled = !busy)
            }
            Button(onClick = { onCreate(name, selectedHome, selectedDrawer, selectedSorter, profile) },
                enabled = !busy && name.isNotBlank() && selectedHome.isNotEmpty() &&
                    selectedDrawer.isNotEmpty() && selectedSorter.isNotEmpty()) {
                Text(if (busy) "Saving…" else "Create and activate launcher")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable private fun ProviderChoices(
    title: String, ids: List<String>, selected: String, enabled: Boolean, choose: (String) -> Unit,
) {
    Text(title)
    if (ids.isEmpty()) Text("No available provider. Install or enable a compatible module.")
    ids.forEach { id ->
        FilterChip(selected == id, { choose(id) }, label = { Text(id) }, enabled = enabled)
    }
}

@Composable
fun CoreManagementPanel(
    contexts: List<LauncherContext>,
    instances: List<ModuleInstance>,
    modules: List<ModuleRecord>,
    security: List<ModuleSecurityStatus>,
    busy: Boolean,
    onRemoveContext: (String) -> Unit,
    onSetModuleEnabled: (ModuleRecord, Boolean) -> Unit,
    onPermission: (ModuleSecurityStatus, CapabilityId, Boolean) -> Unit,
    onSaveInstance: (ModuleInstance, String, String) -> Unit,
    onRenameContext: (LauncherContext, String) -> Unit,
    onRemoveBinding: (LauncherContext, CapabilityId) -> Unit,
) {
    var remove by remember { mutableStateOf<LauncherContext?>(null) }
    var detach by remember { mutableStateOf<Pair<LauncherContext, CapabilityId>?>(null) }
    var pendingGrant by remember { mutableStateOf<Pair<ModuleSecurityStatus, CapabilityId>?>(null) }
    var editing by remember { mutableStateOf<ModuleInstance?>(null) }
    var renaming by remember { mutableStateOf<LauncherContext?>(null) }
    var editedName by remember { mutableStateOf("") }
    var editedJson by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Manage Core", style = MaterialTheme.typography.titleMedium)
            contexts.forEach { context ->
                OutlinedButton({ renaming = context; editedName = context.name }, enabled = !busy) { Text("Rename ${context.name}") }
                OutlinedButton({ remove = context }, enabled = !busy) { Text("Delete ${context.name}") }
                context.bindings.forEach { binding ->
                    val label = instances.find { it.id == binding.instanceId }?.name ?: binding.instanceId
                    OutlinedButton({ detach = context to binding.capability }, enabled = !busy) {
                        Text("Remove $label (${binding.capability.name.substringAfterLast('.')}) from ${context.name}")
                    }
                }
            }
            instances.forEach { instance ->
                OutlinedButton({ editing = instance; editedName = instance.name; editedJson = instance.configurationJson }, enabled = !busy) {
                    Text("Configure ${instance.name}")
                }
            }
            modules.forEach { module ->
                val disabled = module.status == ModuleConnectionStatus.DISABLED
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(module.label, modifier = Modifier.weight(1f))
                    OutlinedButton({ onSetModuleEnabled(module, disabled) }, enabled = !busy) {
                        Text(if (disabled) "Enable" else "Disable")
                    }
                }
                security.find { it.packageName == module.component.packageName }?.let { status ->
                    status.requestedCapabilities.sortedBy { it.wireValue }.forEach { capability ->
                        val granted = capability in status.effectiveGrants
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(capability.wireValue, modifier = Modifier.weight(1f))
                            OutlinedButton({
                                if (granted) onPermission(status, capability, false)
                                else pendingGrant = status to capability
                            }, enabled = !busy && status.identity != null && capability !in status.unsupportedRequests) {
                                Text(if (granted) "Revoke" else "Allow")
                            }
                        }
                    }
                }
            }
        }
    }
    remove?.let { context ->
        AlertDialog(onDismissRequest = { remove = null }, title = { Text("Delete ${context.name}?") },
            text = { Text("This removes the context and its unused instances. Deleting the active context returns to Core recovery.") },
            confirmButton = { TextButton({ remove = null; onRemoveContext(context.id) }) { Text("Delete") } },
            dismissButton = { TextButton({ remove = null }) { Text("Cancel") } })
    }
    detach?.let { (context, capability) ->
        AlertDialog(onDismissRequest = { detach = null }, title = { Text("Remove capability from ${context.name}?") },
            text = { Text("This removes only the selected capability from this context. An unused instance is deleted; shared instances and installed modules remain. If this context is active, the launcher shows settings until you activate a context again.") },
            confirmButton = { TextButton({ detach = null; onRemoveBinding(context, capability) }, enabled = !busy) { Text("Remove") } },
            dismissButton = { TextButton({ detach = null }) { Text("Cancel") } })
    }
    pendingGrant?.let { (status, capability) ->
        AlertDialog(onDismissRequest = { pendingGrant = null }, title = { Text("Allow module capability?") },
            text = { Text("${status.packageName} will be allowed to use ${capability.wireValue}. Signing identity: " +
                status.identity?.signerSha256?.joinToString()) },
            confirmButton = { TextButton({ pendingGrant = null; onPermission(status, capability, true) }) { Text("Allow") } },
            dismissButton = { TextButton({ pendingGrant = null }) { Text("Cancel") } })
    }
    editing?.let { instance ->
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("Configure instance") },
            text = { Column {
                OutlinedTextField(editedName, { editedName = it }, label = { Text("Instance name") })
                Text("Advanced settings are defined by the module. Invalid settings leave Core recovery available.")
                OutlinedTextField(editedJson, { editedJson = it }, label = { Text("Module configuration JSON") }, maxLines = 8)
            } },
            confirmButton = { TextButton({ editing = null; onSaveInstance(instance, editedName, editedJson) },
                enabled = editedName.isNotBlank() && editedJson.length <= 8192) { Text("Save") } },
            dismissButton = { TextButton({ editing = null }) { Text("Cancel") } })
    }
    renaming?.let { context ->
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename context") },
            text = { OutlinedTextField(editedName, { editedName = it }, label = { Text("Context name") }) },
            confirmButton = { TextButton({ renaming = null; onRenameContext(context, editedName) },
                enabled = editedName.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancel") } })
    }
}
