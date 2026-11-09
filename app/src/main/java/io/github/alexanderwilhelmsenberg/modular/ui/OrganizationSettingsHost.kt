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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    onOpenHomeSettings: () -> Unit = {},
) {
    var foldersOpen by remember { mutableStateOf(false) }
    var sorterSetup by remember { mutableStateOf(false) }
    var categoriesRequest by remember { mutableIntStateOf(0) }
    var categoriesBusy by remember { mutableStateOf(false) }
    var categoriesError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val error by runtime.store.error.collectAsState()
    Column {
        SettingsSectionLabel("Organize apps")
        SettingsRow(
            title = "Categories",
            description = "Create and arrange pages for your apps",
            icon = SettingsIcon.CATEGORIES,
            modifier = Modifier.testTag("settings-categories"),
            onClick = { categoriesRequest++ },
            trailing = {
                Switch(
                    checked = snapshot.categoriesEnabled,
                    enabled = !categoriesBusy,
                    onCheckedChange = { checked ->
                        if (!categoriesBusy) {
                            categoriesBusy = true
                            categoriesError = null
                            scope.launch {
                                val result = runCatching {
                                    runtime.update(OrganizationBatch(
                                        listOf(OrganizationEdit.SetCategoriesEnabled(checked)),
                                        expectedRevision = snapshot.revision,
                                    ))
                                }.getOrElse { Result.failure(it) }
                                categoriesBusy = false
                                result.onFailure { categoriesError = it.message ?: "Could not save category settings. Try again." }
                            }
                        }
                    },
                    modifier = Modifier.semantics { contentDescription = "Categories" }
                        .testTag("categories-master-toggle"),
                )
            },
        )
        SettingsRow(
            title = "Folders",
            description = if (mostUsedEnabled) "Below Most Used, above alphabetical apps"
                else "Above alphabetical apps in App drawer",
            icon = SettingsIcon.FOLDER,
            modifier = Modifier.testTag("settings-folders"),
            onClick = { foldersOpen = true },
        )
        SettingsRow(
            title = "Mega Sorter",
            description = "Sort apps into categories side by side",
            icon = SettingsIcon.SORT,
            modifier = Modifier.testTag("settings-mega-sorter"),
            onClick = { sorterSetup = true },
        )
        CategorySettingsUi(snapshot, catalog, runtime::update,
            onOpenHomeSettings = onOpenHomeSettings,
            onManageFolders = { foldersOpen = true }, onStartMegaSorter = { sorterSetup = true },
            showEntry = false, openDetailsRequest = categoriesRequest)
        categoriesError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
