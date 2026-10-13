package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.composition.ActiveContextState
import io.github.alexanderwilhelmsenberg.modular.composition.LauncherContext
import io.github.alexanderwilhelmsenberg.modular.composition.ModuleInstance
import io.github.alexanderwilhelmsenberg.modular.events.CoreEventBusDiagnostics
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.SurfaceDocumentParcel
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleConnectionStatus
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleRecord
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
    homeSurface: SurfaceDocumentParcel?,
    homeSurfaceError: String?,
    onHomeSurfaceAction: (String) -> Unit,
    onRequestHomeRole: () -> Unit,
    onRefreshModules: () -> Unit,
    onRefreshApps: () -> Unit,
    onActivateContext: (String) -> Unit,
    onDeactivateContext: () -> Unit,
) {
    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text("Modular Core")
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                contentPadding =
                    PaddingValues(16.dp),
                verticalArrangement =
                    Arrangement.spacedBy(12.dp),
            ) {
                if (homeSurface != null) {
                    item {
                        Card(
                            modifier =
                                Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier =
                                    Modifier.padding(
                                        20.dp,
                                    ),
                                verticalArrangement =
                                    Arrangement
                                        .spacedBy(
                                            12.dp,
                                        ),
                            ) {
                                Text(
                                    text =
                                        "Active Home surface",
                                    style =
                                        MaterialTheme
                                            .typography
                                            .labelLarge,
                                )
                                SurfaceRenderer(
                                    document =
                                        homeSurface,
                                    onAction =
                                        onHomeSurfaceAction,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth(),
                                )
                            }
                        }
                    }
                } else if (
                    homeSurfaceError != null
                ) {
                    item {
                        StatusCard(
                            title =
                                "Home surface unavailable",
                            body =
                                homeSurfaceError,
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
                        onAction =
                            onRequestHomeRole,
                    )
                }

                item {
                    val connected =
                        modules.count {
                            it.status ==
                                ModuleConnectionStatus
                                    .CONNECTED
                        }

                    StatusCard(
                        title =
                            "Module registry",
                        body =
                            modules.size
                                .toString() +
                                " discovered · " +
                                connected
                                    .toString() +
                                " connected",
                        actionText = "Refresh",
                        onAction =
                            onRefreshModules,
                    )
                }

                item {
                    val appStatus =
                        buildString {
                            append(
                                appCatalog
                                    .apps
                                    .size,
                            )
                            append(
                                " launchable activities · ",
                            )
                            append(
                                appCatalog
                                    .profiles
                                    .size,
                            )
                            append(" profiles")

                            if (
                                appCatalog
                                    .isRefreshing
                            ) {
                                append(
                                    " · refreshing",
                                )
                            }

                            appCatalog
                                .lastError
                                ?.let {
                                    error ->
                                    append(
                                        "
Error: ",
                                    )
                                    append(error)
                                }
                        }

                    StatusCard(
                        title =
                            "App repository",
                        body = appStatus,
                        actionText = "Refresh",
                        onAction =
                            onRefreshApps,
                    )
                }

                item {
                    CompositionStatusCard(
                        instances = instances,
                        contexts = contexts,
                        activeContext =
                            activeContext,
                        loadError =
                            compositionLoadError,
                        activationError =
                            contextActivationError,
                        onDeactivateContext =
                            onDeactivateContext,
                    )
                }

                item {
                    SecurityStatusCard(
                        statuses =
                            moduleSecurity,
                        grantRecordCount =
                            grantRecordCount,
                        loadError =
                            grantLoadError,
                    )
                }

                item {
                    EventBusStatusCard(
                        eventBusDiagnostics,
                    )
                }

                if (contexts.isNotEmpty()) {
                    item {
                        Text(
                            text = "Contexts",
                            style =
                                MaterialTheme
                                    .typography
                                    .titleMedium,
                        )
                    }

                    items(
                        items = contexts,
                        key = { it.id },
                    ) { context ->
                        ContextCard(
                            context = context,
                            activeContext =
                                activeContext,
                            onActivate =
                                onActivateContext,
                        )
                    }
                }

                if (instances.isNotEmpty()) {
                    item {
                        Text(
                            text =
                                "Saved module instances",
                            style =
                                MaterialTheme
                                    .typography
                                    .titleMedium,
                        )
                    }

                    items(
                        items = instances,
                        key = { it.id },
                    ) { instance ->
                        InstanceCard(
                            instance,
                        )
                    }
                }

                item {
                    StatusCard(
                        title =
                            "Recovery surface",
                        body =
                            if (
                                homeSurface == null
                            ) {
                                "No valid Home surface is active. " +
                                    "Core recovery/settings remains available " +
                                    "so a missing or invalid module cannot " +
                                    "strand the launcher."
                            } else {
                                "The active Home above is module-provided. " +
                                    "This diagnostic area remains Core-owned " +
                                    "as the recovery path."
                            },
                    )
                }

                if (modules.isEmpty()) {
                    item {
                        Text(
                            text =
                                "No compatible module services discovered.",
                            style =
                                MaterialTheme
                                    .typography
                                    .bodyMedium,
                        )
                    }
                } else {
                    item {
                        Text(
                            text =
                                "Discovered modules",
                            style =
                                MaterialTheme
                                    .typography
                                    .titleMedium,
                        )
                    }

                    items(
                        items = modules,
                        key = {
                            it.component
                                .flattenToString()
                        },
                    ) { module ->
                        ModuleCard(
                            module = module,
                            securityStatus =
                                moduleSecurity
                                    .firstOrNull {
                                        it.packageName ==
                                            module
                                                .component
                                                .packageName
                                    },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SecurityStatusCard(
    statuses: List<ModuleSecurityStatus>,
    grantRecordCount: Int,
    loadError: String?,
) {
    val body = buildString {
        append(grantRecordCount)
        append(
            " persisted module identities",
        )

        val requested =
            statuses.sumOf {
                it.requestedCapabilities
                    .size
            }
        val effective =
            statuses.sumOf {
                it.effectiveGrants.size
            }

        append("\n")
        append(requested)
        append(
            " requested Core capabilities · ",
        )
        append(effective)
        append(" effective grants")

        val unsupported =
            statuses.sumOf {
                it.unsupportedRequests.size
            }

        if (unsupported > 0) {
            append("\n")
            append(unsupported)
            append(
                " unsupported capability requests",
            )
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
private fun EventBusStatusCard(
    diagnostics: CoreEventBusDiagnostics,
) {
    val body = buildString {
        append("Published sequence: ")
        append(
            diagnostics
                .latestPublishedSequence,
        )
        append("\nDelivered sequence: ")
        append(
            diagnostics
                .latestDeliveredSequence,
        )
        append(
            "
Dropped before delivery: ",
        )
        append(
            diagnostics
                .droppedBeforeDelivery,
        )

        if (
            diagnostics
                .droppedBeforeDelivery > 0
        ) {
            append(
                "
Sequence gaps require a state resync.",
            )
        }
    }

    StatusCard(
        title = "Core event bus",
        body = body,
    )
}

@Composable
private fun CompositionStatusCard(
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

        val active =
            activeContext.context

        if (active == null) {
            append(
                "
No active context",
            )
        } else {
            append("\nActive: ")
            append(active.name)
            append(
                if (
                    activeContext.isReady
                ) {
                    " · ready"
                } else {
                    " · degraded"
                },
            )

            activeContext
                .problems
                .forEach { problem ->
                    append("\n")
                    append(
                        problem
                            .capability
                            .wireValue,
                    )
                    append(": ")
                    append(problem.detail)
                }
        }

        loadError?.let {
            append(
                "
Storage error: ",
            )
            append(it)
        }

        activationError?.let {
            append(
                "
Activation error: ",
            )
            append(it)
        }
    }

    StatusCard(
        title = "Composition",
        body = body,
        actionText =
            if (
                activeContext
                    .context != null
            ) {
                "Deactivate"
            } else {
                null
            },
        onAction =
            onDeactivateContext,
    )
}

@Composable
private fun ContextCard(
    context: LauncherContext,
    activeContext: ActiveContextState,
    onActivate: (String) -> Unit,
) {
    val isActive =
        activeContext.context?.id ==
            context.id

    Card(
        modifier =
            Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier =
                Modifier.padding(16.dp),
            verticalArrangement =
                Arrangement.spacedBy(6.dp),
        ) {
            Text(
                context.name,
                style =
                    MaterialTheme
                        .typography
                        .titleSmall,
            )
            Text(
                context.bindings.size
                    .toString() +
                    " capability bindings",
                style =
                    MaterialTheme
                        .typography
                        .bodySmall,
            )

            context.bindings
                .forEach { binding ->
                    Text(
                        binding
                            .capability
                            .wireValue +
                            " → " +
                            binding.instanceId,
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                    )
                }

            if (isActive) {
                Text(
                    if (
                        activeContext.isReady
                    ) {
                        "Active · ready"
                    } else {
                        "Active · degraded"
                    },
                    style =
                        MaterialTheme
                            .typography
                            .bodyMedium,
                )
            } else {
                Button(
                    onClick = {
                        onActivate(
                            context.id,
                        )
                    },
                ) {
                    Text("Activate")
                }
            }
        }
    }
}

@Composable
private fun InstanceCard(
    instance: ModuleInstance,
) {
    Card(
        modifier =
            Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier =
                Modifier.padding(16.dp),
            verticalArrangement =
                Arrangement.spacedBy(6.dp),
        ) {
            Text(
                instance.name,
                style =
                    MaterialTheme
                        .typography
                        .titleSmall,
            )
            Text(
                "Provider: " +
                    instance.providerId,
                style =
                    MaterialTheme
                        .typography
                        .bodySmall,
            )
            Text(
                "ID: " + instance.id,
                style =
                    MaterialTheme
                        .typography
                        .bodySmall,
            )
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    body: String,
    actionText: String? = null,
    onAction: () -> Unit = {},
) {
    Card(
        modifier =
            Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier =
                Modifier.padding(16.dp),
            verticalArrangement =
                Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style =
                    MaterialTheme
                        .typography
                        .titleMedium,
            )
            Text(
                body,
                style =
                    MaterialTheme
                        .typography
                        .bodyMedium,
            )

            if (actionText != null) {
                Button(
                    onClick = onAction,
                ) {
                    Text(actionText)
                }
            }
        }
    }
}

@Composable
private fun ModuleCard(
    module: ModuleRecord,
    securityStatus:
        ModuleSecurityStatus?,
) {
    Card(
        modifier =
            Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier =
                Modifier.padding(16.dp),
            verticalArrangement =
                Arrangement.spacedBy(6.dp),
        ) {
            Text(
                module.label,
                style =
                    MaterialTheme
                        .typography
                        .titleSmall,
            )
            Text(
                module.component
                    .flattenToShortString(),
                style =
                    MaterialTheme
                        .typography
                        .bodySmall,
            )

            Row(
                horizontalArrangement =
                    Arrangement
                        .spacedBy(8.dp),
            ) {
                Text(
                    "Status: " +
                        module.status
                            .name
                            .lowercase(),
                    style =
                        MaterialTheme
                            .typography
                            .bodyMedium,
                )

                module.moduleVersion
                    ?.let { version ->
                        Text(
                            "Version: " +
                                version,
                            style =
                                MaterialTheme
                                    .typography
                                    .bodyMedium,
                        )
                    }
            }

            module.protocolVersion
                ?.let { version ->
                    Text(
                        "Protocol: " +
                            version,
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                    )
                }

            if (
                module.capabilities
                    .isNotEmpty()
            ) {
                Spacer(
                    Modifier.height(2.dp),
                )
                HorizontalDivider()
                Text(
                    module.capabilities
                        .joinToString(
                            separator = "\n",
                        ),
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall,
                )
            }

            if (
                module
                    .requestedCoreCapabilities
                    .isNotEmpty()
            ) {
                Spacer(
                    Modifier.height(2.dp),
                )
                HorizontalDivider()
                Text(
                    "Requests from Core:",
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall,
                )
                Text(
                    module
                        .requestedCoreCapabilities
                        .joinToString(
                            separator = "\n",
                        ),
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall,
                )
            }

            securityStatus
                ?.let { security ->
                    val identity =
                        security.identity

                    Text(
                        buildString {
                            append(
                                "Identity: ",
                            )
                            append(
                                if (
                                    security
                                        .sameSignerAsCore
                                ) {
                                    "official signer"
                                } else {
                                    "external signer"
                                },
                            )

                            identity
                                ?.signerSha256
                                ?.firstOrNull()
                                ?.let {
                                    digest ->
                                    append(" · ")
                                    append(
                                        digest
                                            .take(12),
                                    )
                                    append("…")
                                }
                        },
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                    )

                    Text(
                        "Effective Core grants: " +
                            if (
                                security
                                    .effectiveGrants
                                    .isEmpty()
                            ) {
                                "none"
                            } else {
                                security
                                    .effectiveGrants
                                    .sortedBy {
                                        it.wireValue
                                    }
                                    .joinToString {
                                        it.wireValue
                                    }
                            },
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                    )

                    if (
                        security
                            .unsupportedRequests
                            .isNotEmpty()
                    ) {
                        Text(
                            "Unsupported requests: " +
                                security
                                    .unsupportedRequests
                                    .sortedBy {
                                        it.wireValue
                                    }
                                    .joinToString {
                                        it.wireValue
                                    },
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .error,
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall,
                        )
                    }

                    security.error
                        ?.let { error ->
                            Text(
                                "Security identity error: " +
                                    error,
                                color =
                                    MaterialTheme
                                        .colorScheme
                                        .error,
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall,
                            )
                        }
                }

            module.error
                ?.let { error ->
                    Text(
                        text = error,
                        color =
                            MaterialTheme
                                .colorScheme
                                .error,
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                    )
                }
        }
    }
}
