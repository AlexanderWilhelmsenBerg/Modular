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
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleConnectionStatus
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleRecord

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModularApp(
    homeRoleAvailable: Boolean,
    homeRoleHeld: Boolean,
    modules: List<ModuleRecord>,
    onRequestHomeRole: () -> Unit,
    onRefreshModules: () -> Unit,
) {
    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text("Modular Core") })
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
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
                        actionText = if (homeRoleAvailable && !homeRoleHeld) {
                            "Make default"
                        } else {
                            null
                        },
                        onAction = onRequestHomeRole,
                    )
                }

                item {
                    val connected = modules.count {
                        it.status == ModuleConnectionStatus.CONNECTED
                    }

                    StatusCard(
                        title = "Module registry",
                        body = modules.size.toString() +
                            " discovered · " +
                            connected.toString() +
                            " connected",
                        actionText = "Refresh",
                        onAction = onRefreshModules,
                    )
                }

                item {
                    StatusCard(
                        title = "Recovery surface",
                        body = "No Home surface module is active yet. " +
                            "This screen is Core's minimal recovery/settings UI, " +
                            "not the production home screen.",
                    )
                }

                if (modules.isEmpty()) {
                    item {
                        Text(
                            text = "No compatible module services discovered.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    item {
                        Text(
                            text = "Discovered modules",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }

                    items(
                        items = modules,
                        key = { it.component.flattenToString() },
                    ) { module ->
                        ModuleCard(module)
                    }
                }
            }
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)

            if (actionText != null) {
                Button(onClick = onAction) {
                    Text(actionText)
                }
            }
        }
    }
}

@Composable
private fun ModuleCard(module: ModuleRecord) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(module.label, style = MaterialTheme.typography.titleSmall)
            Text(
                module.component.flattenToShortString(),
                style = MaterialTheme.typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Status: " + module.status.name.lowercase(),
                    style = MaterialTheme.typography.bodyMedium,
                )
                module.moduleVersion?.let { version ->
                    Text(
                        "Version: " + version,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            module.protocolVersion?.let { version ->
                Text(
                    "Protocol: " + version,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (module.capabilities.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                HorizontalDivider()
                Text(
                    module.capabilities.joinToString(separator = "\n"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            module.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
