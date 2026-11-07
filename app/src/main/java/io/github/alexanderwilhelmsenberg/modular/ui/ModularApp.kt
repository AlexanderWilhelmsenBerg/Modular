package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.appearance.AppearanceTheme
import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.composition.ActiveContextState
import io.github.alexanderwilhelmsenberg.modular.composition.LauncherContext
import io.github.alexanderwilhelmsenberg.modular.composition.ModuleInstance
import io.github.alexanderwilhelmsenberg.modular.events.CoreEventBusDiagnostics
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleManagementEvent
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleRecord
import io.github.alexanderwilhelmsenberg.modular.moduleapi.CapabilityId
import io.github.alexanderwilhelmsenberg.modular.security.ModuleSecurityStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModularApp(
    homeRoleAvailable: Boolean,
    homeRoleHeld: Boolean,
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
    onRequestHomeRole: () -> Unit,
    onRefreshModules: () -> Unit,
    onRefreshApps: () -> Unit,
    onActivateContext: (String) -> Unit,
    onDeactivateContext: () -> Unit,
    onBack: () -> Unit = {},
    appearanceTheme: AppearanceTheme = AppearanceTheme.LIGHT,
    appearanceError: String? = null,
    onAppearanceThemeSelected: (AppearanceTheme) -> Unit = {},
    managementEvents: List<ModuleManagementEvent> = emptyList(),
    onSetModuleEnabled: (ModuleRecord, Boolean) -> Unit = { _, _ -> },
    onRetryModule: (ModuleRecord) -> Unit = {},
    onUninstallModule: (ModuleRecord) -> Unit = {},
    onSetGrant: (ModuleRecord, CapabilityId, Boolean) -> Unit = { _, _, _ -> },
    onOpenModuleSettings: (ModuleRecord) -> Unit = {},
    onClearModuleEvents: () -> Unit = {},
    compositionContent: @Composable () -> Unit = {},
    appearanceContent: @Composable () -> Unit = {},
    corePackageName: String = "io.github.alexanderwilhelmsenberg.modular",
) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Settings") },
                    navigationIcon = {
                        androidx.compose.material3.TextButton(onClick = onBack) {
                            Text("Back")
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement =
                    Arrangement.spacedBy(12.dp),
            ) {
                item(key = "appearance") {
                    AppearanceSelector(
                        selected = appearanceTheme,
                        error = appearanceError,
                        onSelect = onAppearanceThemeSelected,
                    )
                }

                item(key = "home-appearance") { appearanceContent() }

                item {
                    Text(
                        "Add-on modules",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }

                item {
                    ModuleEventLog(
                        events = managementEvents,
                        modules = modules,
                        onClear = onClearModuleEvents,
                    )
                }

                item {
                    androidx.compose.material3.TextButton(onClick = onRefreshModules) {
                        Text("Refresh module list")
                    }
                }

                items(
                    items = modules,
                    key = { it.component.flattenToString() },
                ) { module ->
                    ModuleManagementCard(
                        module = module,
                        securityStatus = moduleSecurity.firstOrNull {
                            it.packageName == module.component.packageName
                        },
                        corePackageName = corePackageName,
                        onSetEnabled = onSetModuleEnabled,
                        onRetry = onRetryModule,
                        onUninstall = onUninstallModule,
                        onSetGrant = onSetGrant,
                        onOpenSettings = onOpenModuleSettings,
                    )
                }

                if (modules.isEmpty()) {
                    item {
                        Text(
                            "No compatible module services discovered.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                item {
                    StatusCard(
                        title = "Launcher role",
                        body = when {
                            !homeRoleAvailable ->
                                "HOME role is unavailable on this device."
                            homeRoleHeld ->
                                "Modular is the active HOME application."
                            else ->
                                "Modular is not the current HOME application."
                        },
                        actionText =
                            if (
                                homeRoleAvailable &&
                                !homeRoleHeld
                            ) {
                                "Make default"
                            } else {
                                null
                            },
                        onAction = onRequestHomeRole,
                    )
                }

                item {
                    compositionContent()
                }

                item {
                    StatusCard(
                        title = "Home",
                        body = "Home is built in and works without add-ons.",
                    )
                }

                item {
                    DiagnosticsSection(
                        modules = modules,
                        appCatalog = appCatalog,
                        instances = instances,
                        contexts = contexts,
                        activeContext = activeContext,
                        compositionLoadError = compositionLoadError,
                        contextActivationError = contextActivationError,
                        eventBusDiagnostics = eventBusDiagnostics,
                        moduleSecurity = moduleSecurity,
                        grantRecordCount = grantRecordCount,
                        grantLoadError = grantLoadError,
                        onRefreshModules = onRefreshModules,
                        onRefreshApps = onRefreshApps,
                        onActivateContext = onActivateContext,
                        onDeactivateContext = onDeactivateContext,
                    )
                }

            }
        }
}

@Composable
internal fun AppearanceSelector(
    selected: AppearanceTheme,
    error: String?,
    onSelect: (AppearanceTheme) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Appearance", style = MaterialTheme.typography.titleLarge)
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            AppearanceTheme.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = selected == option,
                            onClick = { onSelect(option) },
                            role = Role.RadioButton,
                        )
                        .testTag("appearance-${option.id}")
                        .padding(vertical = 4.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected == option, onClick = null)
                    androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                    Text(option.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
internal fun SecurityStatusCard(
    statuses: List<ModuleSecurityStatus>,
    grantRecordCount: Int,
    loadError: String?,
) {
    val body = buildString {
        append(grantRecordCount)
        append(" persisted module identities")

        val requested = statuses.sumOf {
            it.requestedCapabilities.size
        }
        val effective = statuses.sumOf {
            it.effectiveGrants.size
        }

        append("\n")
        append(requested)
        append(" requested Core capabilities · ")
        append(effective)
        append(" effective grants")

        val unsupported = statuses.sumOf {
            it.unsupportedRequests.size
        }
        if (unsupported > 0) {
            append("\n")
            append(unsupported)
            append(" unsupported capability requests")
        }

        loadError?.let { error ->
            append("\nStorage error: ")
            append(error)
        }
    }

    StatusCard(
        title = "Module security",
        body = body,
    )
}

@Composable
internal fun EventBusStatusCard(
    diagnostics: CoreEventBusDiagnostics,
) {
    val body = buildString {
        append("Published sequence: ")
        append(diagnostics.latestPublishedSequence)
        append("\nDelivered sequence: ")
        append(diagnostics.latestDeliveredSequence)
        append("\nDropped before delivery: ")
        append(diagnostics.droppedBeforeDelivery)

        if (diagnostics.droppedBeforeDelivery > 0) {
            append(
                "\nSequence gaps require a state resync.",
            )
        }
    }

    StatusCard(
        title = "Core event bus",
        body = body,
    )
}

@Composable
internal fun CompositionStatusCard(
    instances: List<ModuleInstance>,
    contexts: List<LauncherContext>,
    activeContext: ActiveContextState,
    loadError: String?,
    activationError: String?,
    onDeactivateContext: () -> Unit,
) {
    val body = buildString {
        append(instances.size)
        append(" instances · ")
        append(contexts.size)
        append(" contexts")

        val active = activeContext.context
        if (active == null) {
            append("\nNo active context")
        } else {
            append("\nActive: ")
            append(active.name)
            append(
                if (activeContext.isReady) {
                    " · ready"
                } else {
                    " · degraded"
                },
            )

            activeContext.problems.forEach { problem ->
                append("\n")
                append(problem.capability.wireValue)
                append(": ")
                append(problem.detail)
            }
        }

        loadError?.let {
            append("\nStorage error: ")
            append(it)
        }

        activationError?.let {
            append("\nActivation error: ")
            append(it)
        }
    }

    StatusCard(
        title = "Composition",
        body = body,
        actionText =
            if (activeContext.context != null) {
                "Deactivate"
            } else {
                null
            },
        onAction = onDeactivateContext,
    )
}

@Composable
internal fun ContextCard(
    context: LauncherContext,
    activeContext: ActiveContextState,
    onActivate: (String) -> Unit,
) {
    val isActive = activeContext.context?.id == context.id

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement =
                Arrangement.spacedBy(6.dp),
        ) {
            Text(
                context.name,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                context.bindings.size.toString() +
                    " capability bindings",
                style = MaterialTheme.typography.bodySmall,
            )

            context.bindings.forEach { binding ->
                Text(
                    binding.capability.wireValue +
                        " → " + binding.instanceId,
                    style =
                        MaterialTheme.typography.bodySmall,
                )
            }

            if (isActive) {
                Text(
                    if (activeContext.isReady) {
                        "Active · ready"
                    } else {
                        "Active · degraded"
                    },
                    style =
                        MaterialTheme.typography.bodyMedium,
                )
            } else {
                Button(
                    onClick = {
                        onActivate(context.id)
                    },
                ) {
                    Text("Activate")
                }
            }
        }
    }
}

@Composable
internal fun InstanceCard(
    instance: ModuleInstance,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement =
                Arrangement.spacedBy(6.dp),
        ) {
            Text(
                instance.name,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "Provider: " + instance.providerId,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "ID: " + instance.id,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun StatusCard(
    title: String,
    body: String,
    actionText: String? = null,
    onAction: () -> Unit = {},
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement =
                Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
            )

            if (actionText != null) {
                Button(onClick = onAction) {
                    Text(actionText)
                }
            }
        }
    }
}
