package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.mcp.*

@Composable
fun McpBrokerPanel(
    state: BrokerState,
    busy: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPolicy: (BrokerPolicy) -> Unit,
    onRefreshTools: () -> Unit,
) {
    var approval by remember { mutableStateOf<ModuleToolCandidate?>(null) }
    val clipboard = LocalClipboardManager.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("MCP access", style = MaterialTheme.typography.titleMedium)
            Text("Off by default. Starting enables authenticated access on this device's loopback interface. Keep the pairing token private.")
            if (state.endpoint == null) {
                Button(onStart, enabled = !busy) { Text("Start local MCP") }
            } else {
                Text(state.endpoint)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ state.token?.let { clipboard.setText(AnnotatedString(it)) } }, enabled = !busy) { Text("Copy pairing token") }
                    Button(onStop, enabled = !busy) { Text("Stop and revoke token") }
                }
            }
            Row {
                Checkbox(state.policy.allowContextChanges,
                    { onPolicy(state.policy.copy(allowContextChanges = it)) }, enabled = !busy)
                Text("Allow remote context switching")
            }
            Row {
                Checkbox(state.policy.allowAppLaunch,
                    { onPolicy(state.policy.copy(allowAppLaunch = it)) }, enabled = !busy)
                Text("Allow remote app launching")
            }
            OutlinedButton(onRefreshTools, enabled = !busy) { Text("Refresh module tool proposals") }
            state.tools.forEach { tool ->
                val approved = tool.approvalKey in state.policy.approvedModuleTools
                Text(tool.providerId + " · " + tool.name + "\n" + tool.description)
                OutlinedButton({
                    if (approved) onPolicy(state.policy.copy(approvedModuleTools = state.policy.approvedModuleTools - tool.approvalKey))
                    else approval = tool
                }, enabled = !busy) { Text(if (approved) "Revoke remote access" else "Review remote access") }
            }
            if (state.policy.approvedModuleTools.isNotEmpty()) {
                OutlinedButton({ onPolicy(state.policy.copy(approvedModuleTools = emptySet())) }, enabled = !busy) {
                    Text("Revoke all module exports")
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Recent audit events", style = MaterialTheme.typography.labelLarge)
            state.audit.takeLast(8).reversed().forEach { item ->
                Text(item.tool + " · " + item.decision + " · " + item.durationMs + " ms",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    approval?.let { tool ->
        AlertDialog(onDismissRequest = { approval = null }, title = { Text("Expose module read operation?") },
            text = { Column {
                Text(tool.name + " · " + tool.description)
                Text("The module declares this operation read-only. Approving exposes it as a tool and, when it needs no arguments, a resource. The module retains its existing Android and Core permissions.")
                Text("Instance: " + tool.instanceId)
                Text("Signing identity and version: " + tool.identityEvidence)
                Text("Input schema: " + tool.schema.toString())
            } },
            confirmButton = { TextButton({
                approval = null
                onPolicy(state.policy.copy(approvedModuleTools = state.policy.approvedModuleTools + tool.approvalKey))
            }) { Text("Allow remote read") } },
            dismissButton = { TextButton({ approval = null }) { Text("Cancel") } })
    }
}
