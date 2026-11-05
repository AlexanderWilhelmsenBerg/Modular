package io.github.alexanderwilhelmsenberg.modular.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.composition.ActiveContextState
import io.github.alexanderwilhelmsenberg.modular.composition.LauncherContext
import io.github.alexanderwilhelmsenberg.modular.composition.ModuleInstance
import io.github.alexanderwilhelmsenberg.modular.events.CoreEventBusDiagnostics
import io.github.alexanderwilhelmsenberg.modular.moduleapi.CapabilityId
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleConnectionStatus
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleManagementEvent
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleRecord
import io.github.alexanderwilhelmsenberg.modular.security.ModuleSecurityStatus
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ModuleManagementCard(
    module: ModuleRecord,
    securityStatus: ModuleSecurityStatus?,
    corePackageName: String,
    onSetEnabled: (ModuleRecord, Boolean) -> Unit,
    onRetry: (ModuleRecord) -> Unit,
    onUninstall: (ModuleRecord) -> Unit,
    onSetGrant: (ModuleRecord, CapabilityId, Boolean) -> Unit,
) {
    var showUninstall by remember(module.component) { mutableStateOf(false) }
    var detailsExpanded by remember(module.component) { mutableStateOf(false) }
    val isCore = module.component.packageName == corePackageName
    val canRetry = !isCore && module.enabled && module.status in setOf(
        ModuleConnectionStatus.FAILED,
        ModuleConnectionStatus.DISCONNECTED,
        ModuleConnectionStatus.INCOMPATIBLE,
    )
    val grantsLocked = securityStatus?.identity == null ||
        securityStatus.error != null

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(module.label, style = MaterialTheme.typography.titleMedium)
            Text(
                module.component.flattenToShortString(),
                style = MaterialTheme.typography.bodySmall,
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Module service", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (module.enabled) "Enabled · ${module.status.name.lowercase()}"
                        else "Disabled",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = module.enabled,
                    enabled = !isCore,
                    onCheckedChange = { onSetEnabled(module, it) },
                    modifier = Modifier.semantics {
                        contentDescription = "Enable ${module.label}"
                    },
                )
            }

            module.error?.let { message ->
                Text(
                    "Problem: $message",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
                Text(if (detailsExpanded) "Hide module details" else "Module details")
            }
            if (detailsExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    module.moduleVersion?.let { Text("Version: $it", style = MaterialTheme.typography.bodySmall) }
                    module.protocolVersion?.let { Text("Protocol: $it", style = MaterialTheme.typography.bodySmall) }
                    Text(
                        "Provides: " + (module.capabilities.takeIf { it.isNotEmpty() }?.joinToString() ?: "none reported"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Requests from Core: " +
                            (module.requestedCoreCapabilities.takeIf { it.isNotEmpty() }?.joinToString() ?: "none"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    securityStatus?.let { status ->
                        Text(
                            if (status.sameSignerAsCore) "Signing identity: same as Core" else "Signing identity: external module",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        status.identity?.signerSha256?.firstOrNull()?.let { digest ->
                            Text("Signer SHA-256: $digest", style = MaterialTheme.typography.bodySmall)
                        }
                        status.unsupportedRequests.takeIf { it.isNotEmpty() }?.let { unsupported ->
                            Text(
                                "Unsupported requests: ${unsupported.sortedBy { it.wireValue }.joinToString { it.wireValue }}",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Actions", style = MaterialTheme.typography.labelLarge)
                Button(
                    onClick = { onRetry(module) },
                    enabled = canRetry,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Retry connection") }
                OutlinedButton(
                    onClick = { showUninstall = true },
                    enabled = !isCore,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Uninstall add-on") }
            }

            if (securityStatus != null) {
                HorizontalDivider()
                Text("Core capability access", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Grants are scoped to this package and its verified signing identity.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (securityStatus.explicitGrants.any { it !in securityStatus.effectiveGrants }) {
                    Text(
                        "Some saved permissions are not currently requested or available. You can still revoke them.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (grantsLocked) {
                    Text(
                        securityStatus.error ?: "The module identity could not be verified. Grants are unavailable.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                val capabilities = (securityStatus.requestedCapabilities +
                    securityStatus.unsupportedRequests + securityStatus.explicitGrants)
                    .sortedBy { it.wireValue }
                if (capabilities.isEmpty()) {
                    Text("No Core capabilities requested.", style = MaterialTheme.typography.bodySmall)
                }
                capabilities.forEach { capability ->
                    CapabilityGrantRow(
                        capability = capability,
                        granted = capability in securityStatus.explicitGrants,
                        unsupported = capability in securityStatus.unsupportedRequests,
                        enabled = if (capability in securityStatus.explicitGrants) {
                            true
                        } else {
                            !grantsLocked &&
                                capability in securityStatus.requestedCapabilities &&
                                capability !in securityStatus.unsupportedRequests
                        },
                        onChanged = { onSetGrant(module, capability, it) },
                    )
                }
            }
        }
    }

    if (showUninstall) {
        AlertDialog(
            onDismissRequest = { showUninstall = false },
            title = { Text("Uninstall ${module.label}?") },
            text = {
                Text("Android will uninstall the whole add-on APK, including all module services it provides. You will review the system uninstall dialog next.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showUninstall = false
                    onUninstall(module)
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { showUninstall = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun CapabilityGrantRow(
    capability: CapabilityId,
    granted: Boolean,
    unsupported: Boolean,
    enabled: Boolean,
    onChanged: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(capability.wireValue, style = MaterialTheme.typography.bodySmall)
            if (unsupported) {
                Text(
                    "Unsupported by this Core version",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Switch(
            checked = granted,
            enabled = enabled,
            onCheckedChange = onChanged,
            modifier = Modifier.semantics {
                contentDescription = "Allow ${capability.wireValue}"
            },
        )
    }
}

@Composable
internal fun DiagnosticsSection(
    modules: List<ModuleRecord>,
    appCatalog: AppCatalog,
    instances: List<ModuleInstance>,
    contexts: List<LauncherContext>,
    activeContext: ActiveContextState,
    compositionLoadError: String?,
    contextActivationError: String?,
    eventBusDiagnostics: CoreEventBusDiagnostics,
    moduleSecurity: List<ModuleSecurityStatus>,
    grantRecordCount: Int,
    grantLoadError: String?,
    onRefreshModules: () -> Unit,
    onRefreshApps: () -> Unit,
    onActivateContext: (String) -> Unit,
    onDeactivateContext: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide diagnostics" else "Diagnostics")
        }
        if (expanded) {
            val connected = modules.count { it.status == ModuleConnectionStatus.CONNECTED }
            StatusCard(
                title = "Module registry",
                body = "${modules.size} discovered · $connected connected",
                actionText = "Refresh",
                onAction = onRefreshModules,
            )
            val appStatus = buildString {
                append("${appCatalog.apps.size} launchable activities · ${appCatalog.profiles.size} profiles")
                if (appCatalog.isRefreshing) append(" · refreshing")
                appCatalog.lastError?.let { append("\nError: $it") }
            }
            StatusCard("App repository", appStatus, "Refresh", onRefreshApps)
            CompositionStatusCard(
                instances, contexts, activeContext, compositionLoadError,
                contextActivationError, onDeactivateContext,
            )
            SecurityStatusCard(moduleSecurity, grantRecordCount, grantLoadError)
            EventBusStatusCard(eventBusDiagnostics)
            if (contexts.isNotEmpty()) {
                Text("Contexts", style = MaterialTheme.typography.titleMedium)
                contexts.forEach { context ->
                    ContextCard(context, activeContext, onActivateContext)
                }
            }
            if (instances.isNotEmpty()) {
                Text("Saved module instances", style = MaterialTheme.typography.titleMedium)
                instances.forEach { instance -> InstanceCard(instance) }
            }
        }
    }
}

@Composable
internal fun ModuleEventLog(
    events: List<ModuleManagementEvent>,
    modules: List<ModuleRecord>,
    onClear: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var errorsOnly by remember { mutableStateOf(false) }
    var selectedModule by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val ordered = events.asReversed()
    val filtered = ordered.filter { event ->
        (!errorsOnly || event.isError) &&
            (selectedModule == null || event.component == selectedModule)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide module event log" else "Module event log · ${events.size}")
            }
            if (expanded) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChipButton("All", !errorsOnly) { errorsOnly = false }
                    FilterChipButton("Errors", errorsOnly) { errorsOnly = true }
                }
                Column {
                    OutlinedButton(onClick = { menuOpen = true }) {
                        Text(selectedModule?.substringAfterLast('/') ?: "All modules")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("All modules") },
                            onClick = { selectedModule = null; menuOpen = false },
                        )
                        val moduleChoices = buildMap {
                            modules.forEach { module ->
                                put(module.component.flattenToShortString(), module.label)
                                put(module.component.packageName, module.component.packageName)
                            }
                            events.forEach { event ->
                                if (!containsKey(event.component)) {
                                    put(event.component, event.component)
                                }
                            }
                        }
                        moduleChoices.forEach { (component, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    selectedModule = component
                                    menuOpen = false
                                },
                            )
                        }
                    }
                }
                Text("${filtered.size} events", style = MaterialTheme.typography.labelMedium)
                filtered.forEach { event ->
                    EventRow(event)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { copyEvents(context, filtered) }) {
                        Text("Copy filtered log")
                    }
                    OutlinedButton(
                        onClick = { confirmClear = true },
                        enabled = events.isNotEmpty(),
                    ) { Text("Clear log") }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear module event log?") },
            text = { Text("This permanently clears the local module management history.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClear() }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun FilterChipButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick) { Text(label) }
    else OutlinedButton(onClick = onClick) { Text(label) }
}

@Composable
private fun EventRow(event: ModuleManagementEvent) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                .format(Date(event.timestampMillis)),
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            "${event.stage} · ${event.component}",
            style = MaterialTheme.typography.bodyMedium,
            color = if (event.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(event.message, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
    }
}

private fun copyEvents(context: Context, events: List<ModuleManagementEvent>) {
    val text = events.joinToString("\n") { event ->
        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
            .format(Date(event.timestampMillis))
        "$time ${if (event.isError) "ERROR " else ""}${event.stage} ${event.component}: ${event.message}"
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Module events", text))
}
