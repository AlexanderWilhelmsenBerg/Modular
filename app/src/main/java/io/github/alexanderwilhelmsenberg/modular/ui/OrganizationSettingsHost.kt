package io.github.alexanderwilhelmsenberg.modular.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.organization.*
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun OrganizationSettingsHost(
    snapshot: OrganizationSnapshot,
    catalog: List<LaunchableApp>,
    runtime: OrganizationRuntime,
    mostUsedEnabled: Boolean,
    onStartSorter: (String?, String?) -> Unit,
) {
    var foldersOpen by remember { mutableStateOf(false) }
    var sorterSetup by remember { mutableStateOf(false) }
    val error by runtime.store.error.collectAsState()
    Column {
        CategorySettingsUi(snapshot, catalog, runtime::update,
            onManageFolders = { foldersOpen = true }, onStartMegaSorter = { sorterSetup = true })
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (foldersOpen) OrganizationFoldersDialog(snapshot, catalog, runtime, mostUsedEnabled,
        onDismiss = { foldersOpen = false })
    if (sorterSetup) OrganizationSorterSetup(snapshot, runtime,
        onStart = { left, right -> sorterSetup = false; onStartSorter(left, right) },
        onDismiss = { sorterSetup = false })
}

@Composable
fun OrganizationSorterSetup(snapshot: OrganizationSnapshot, runtime: OrganizationRuntime,
    onStart: (String?, String?) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    MegaSorterSetup(snapshot.categories,
        onCreateCategory = { name -> scope.launch {
            runtime.update(OrganizationBatch(listOf(OrganizationEdit.CreateCategory(
                CategoryDefinition(UUID.randomUUID().toString(), name.trim())))))
                .onFailure { error = it.message ?: "Could not create category" }
        } }, onStart = onStart, onDismiss = onDismiss)
    error?.let { message -> AlertDialog(onDismissRequest = { error = null },
        title = { Text("Could not create category") }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { error = null }) { Text("Close") } }) }
}

@Composable
fun OrganizationFoldersDialog(
    snapshot: OrganizationSnapshot,
    catalog: List<LaunchableApp>,
    runtime: OrganizationRuntime,
    mostUsedEnabled: Boolean,
    initialFolderId: String? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val statuses by runtime.signals.statuses.collectAsState()
    var permissionDenied by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionDenied = result.values.none { it }
        runtime.signals.start(snapshot.folders)
    }
    val requestLocation = {
        permissions.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Folders") },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            ManageFoldersUi(snapshot, catalog, mostUsedEnabled, runtime::update,
                conditionsForFolder = { folder -> folder.conditionIds.mapNotNull(runtime.conditions::load) },
                onSaveFolder = { folder, rules -> runtime.saveFolder(folder, rules, snapshot.revision) },
                smartSettings = { folder, rules, onFolder, onRules ->
                    FolderRuleSettings(folder, rules, statuses[folder.id], onFolder, onRules,
                        onRequestLocationPermission = requestLocation, onRequestWifiPermission = requestLocation)
                },
                statusForFolder = { folderVisibilitySummary(it, statuses[it.id]) },
                initialFolderId = initialFolderId)
            if (permissionDenied) {
                Text("Permission was not granted. You can keep using other folder rules or change access in Android settings.")
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }) { Text("Open app permissions") }
            }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}
