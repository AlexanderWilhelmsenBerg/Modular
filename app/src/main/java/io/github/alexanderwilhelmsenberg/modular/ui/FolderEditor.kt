package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.organization.FolderCondition
import io.github.alexanderwilhelmsenberg.modular.organization.FolderDefinition
import io.github.alexanderwilhelmsenberg.modular.organization.FolderPlacement
import io.github.alexanderwilhelmsenberg.modular.organization.FolderVisibilityMode
import io.github.alexanderwilhelmsenberg.modular.organization.HomePinRef
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationBatch
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationEdit
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationLimits
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationSnapshot
import java.util.UUID
import kotlinx.coroutines.launch

/** Settings list deliberately uses saved definitions, including every hidden folder. */
@Composable
fun ManageFoldersUi(
    snapshot: OrganizationSnapshot,
    catalog: List<LaunchableApp>,
    mostUsedEnabled: Boolean,
    onUpdate: suspend (OrganizationBatch) -> Result<OrganizationSnapshot>,
    hiddenKeys: Set<AppKey> = emptySet(),
    initialFolderId: String? = null,
    onOpenFolderPreview: ((FolderDefinition) -> Unit)? = null,
    conditionsForFolder: (FolderDefinition) -> List<FolderCondition> = { emptyList() },
    onSaveFolder: (suspend (FolderDefinition, List<FolderCondition>) -> Result<Unit>)? = null,
    smartSettings: @Composable (FolderDefinition, List<FolderCondition>, (FolderDefinition) -> Unit,
        (List<FolderCondition>) -> Unit) -> Unit = { _, _, _, _ -> },
    statusForFolder: (FolderDefinition) -> String = { folder ->
        when (folder.visibilityMode) {
            FolderVisibilityMode.ALWAYS -> "Always shown"
            FolderVisibilityMode.HIDDEN -> "Manually hidden"
            FolderVisibilityMode.SMART -> "Smart visibility"
        }
    },
) {
    var editingId by remember { mutableStateOf<String?>(null) }
    var previewId by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var pinBusy by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val newFolder = remember(creating) { FolderDefinition(UUID.randomUUID().toString(), "") }
    val editing = snapshot.folders.firstOrNull { it.id == editingId }
    val preview = snapshot.folders.firstOrNull { it.id == previewId }
    LaunchedEffect(initialFolderId) {
        if (initialFolderId != null) editingId = initialFolderId
    }
    fun movePin(folder: FolderDefinition, delta: Int) {
        if (pinBusy) return
        val folderIndices = snapshot.pins.indices.filter { snapshot.pins[it] is HomePinRef.Folder }
        val position = folderIndices.indexOf(snapshot.pins.indexOf(HomePinRef.Folder(folder.id)))
        val target = position + delta
        if (position < 0 || target !in folderIndices.indices) return
        val reordered = snapshot.pins.toMutableList().apply {
            val from = folderIndices[position]
            val to = folderIndices[target]
            val other = this[to]
            this[to] = this[from]
            this[from] = other
        }
        pinBusy = true
        pinError = null
        scope.launch {
            val result = runCatching {
                onUpdate(OrganizationBatch(listOf(OrganizationEdit.SetPins(reordered)), snapshot.revision))
            }.getOrElse { Result.failure(it) }
            pinBusy = false
            result.onFailure { pinError = it.message ?: "Could not reorder shortcuts. Try again." }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("manage-folders")) {
        Text("Manage folders", style = MaterialTheme.typography.titleLarge)
        Text(if (mostUsedEnabled) "Folders appear below Most Used and above alphabetical apps in App drawer."
            else "Folders appear above alphabetical apps in App drawer.",
            style = MaterialTheme.typography.bodySmall)
        if (snapshot.folders.isEmpty()) Text("No folders yet.")
        snapshot.folders.forEach { folder ->
            OutlinedButton(onClick = { editingId = folder.id }, modifier = Modifier.fillMaxWidth()
                .testTag("manage-folder:${folder.id}")) {
                Column(Modifier.fillMaxWidth()) {
                    Text(folder.name, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text("${if (folder.placement == FolderPlacement.MOST_USED) "Pinned order" else "Alphabetical order"} · ${statusForFolder(folder)} · ${folder.members.size} apps",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = {
                if (onOpenFolderPreview != null) onOpenFolderPreview(folder) else previewId = folder.id
            }, modifier = Modifier.testTag("preview-folder:${folder.id}")) { Text("Preview ${folder.name}") }
            if (folder.placement == FolderPlacement.MOST_USED) {
                val folderPins = snapshot.pins.filterIsInstance<HomePinRef.Folder>()
                val position = folderPins.indexOf(HomePinRef.Folder(folder.id))
                Row {
                    TextButton(enabled = !pinBusy && position > 0,
                        onClick = { movePin(folder, -1) },
                        modifier = Modifier.testTag("folder-pin-up:${folder.id}")) { Text("Move up") }
                    TextButton(enabled = !pinBusy && position >= 0 && position < folderPins.lastIndex,
                        onClick = { movePin(folder, 1) },
                        modifier = Modifier.testTag("folder-pin-down:${folder.id}")) { Text("Move down") }
                }
            }
        }
        OutlinedButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()
            .testTag("create-folder")) { Text("Create folder") }
        pinError?.let { Text(it, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("folder-pin-error")) }
    }
    if (creating) FolderEditor(
        folder = newFolder,
        isNew = true, snapshot = snapshot, catalog = catalog, mostUsedEnabled = mostUsedEnabled,
        hiddenKeys = hiddenKeys,
        initialConditions = emptyList(), smartSettings = smartSettings,
        onDismiss = { creating = false }, onSave = { folder, conditions ->
            if (onSaveFolder != null) onSaveFolder(folder, conditions)
            else if (conditions.isNotEmpty()) Result.failure(IllegalStateException("Smart rule saving is unavailable."))
            else onUpdate(OrganizationBatch(listOf(OrganizationEdit.CreateFolder(folder)), snapshot.revision)).map { Unit }
        },
        onDelete = null,
    )
    if (editing != null) FolderEditor(
        folder = editing, isNew = false, snapshot = snapshot, catalog = catalog,
        mostUsedEnabled = mostUsedEnabled,
        hiddenKeys = hiddenKeys,
        initialConditions = conditionsForFolder(editing), smartSettings = smartSettings,
        onDismiss = { editingId = null },
        onSave = { folder, conditions ->
            if (onSaveFolder != null) onSaveFolder(folder, conditions)
            else if (conditions != conditionsForFolder(editing))
                Result.failure(IllegalStateException("Smart rule saving is unavailable."))
            else onUpdate(OrganizationBatch(listOf(OrganizationEdit.UpdateFolder(folder)), snapshot.revision)).map { Unit }
        },
        onDelete = {
            onUpdate(OrganizationBatch(listOf(OrganizationEdit.DeleteFolder(editing.id)), snapshot.revision)).map { Unit }
        },
    )
    if (preview != null) AlertDialog(
        onDismissRequest = { previewId = null },
        title = { Text(preview.name) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                if (preview.members.isEmpty()) Text("This folder is empty.")
                preview.members.forEach { key ->
                    val app = catalog.firstOrNull { it.key == key }
                    Text(app?.label ?: key.packageName)
                    if (key in hiddenKeys) Text("Hidden app", style = MaterialTheme.typography.bodySmall)
                    else if (app == null || !app.enabled) Text("Unavailable · ${key.activityName} · profile ${key.profileSerial}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { previewId = null; editingId = preview.id }) { Text("Edit") } },
        dismissButton = { TextButton(onClick = { previewId = null }) { Text("Close") } },
    )
}

@Composable
fun FolderEditor(
    folder: FolderDefinition,
    isNew: Boolean,
    snapshot: OrganizationSnapshot,
    catalog: List<LaunchableApp>,
    mostUsedEnabled: Boolean,
    hiddenKeys: Set<AppKey> = emptySet(),
    initialConditions: List<FolderCondition>,
    smartSettings: @Composable (FolderDefinition, List<FolderCondition>, (FolderDefinition) -> Unit,
        (List<FolderCondition>) -> Unit) -> Unit,
    onSave: suspend (FolderDefinition, List<FolderCondition>) -> Result<Unit>,
    onDelete: (suspend () -> Result<Unit>)?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var draft by remember(folder.id) { mutableStateOf(folder) }
    var conditions by remember(folder.id) { mutableStateOf(initialConditions) }
    var pickerOpen by remember(folder.id) { mutableStateOf(false) }
    var query by remember(folder.id) { mutableStateOf("") }
    var deleteConfirm by remember(folder.id) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val byKey = remember(catalog) { catalog.associateBy { it.key } }
    val normalized = draft.name.trim()
    val invalidName = organizationNameInvalid(normalized,
        snapshot.folders.filter { it.id != draft.id }.map { it.name })
    val invalidSmart = draft.visibilityMode == FolderVisibilityMode.SMART && conditions.none { it.enabled }

    fun submit(save: suspend () -> Result<Unit>) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            val result = runCatching { save() }.getOrElse { Result.failure(it) }
            busy = false
            result.onSuccess { onDismiss() }.onFailure { error = it.message ?: "Could not save folder. Try again." }
        }
    }

    AlertDialog(
        modifier = Modifier.testTag("folder-editor:${draft.id}"),
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (isNew) "Create folder" else "Folder settings") },
        text = {
            Column(Modifier.heightIn(max = 580.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(draft.name, {
                    if (it.codePointCount(0, it.length) <= OrganizationLimits.MAX_NAME_CODE_POINTS) draft = draft.copy(name = it)
                },
                    label = { Text("Folder name") }, singleLine = true,
                    isError = draft.name.isNotBlank() && invalidName,
                    modifier = Modifier.fillMaxWidth().testTag("folder-name"))
                if (draft.name.isNotBlank() && invalidName) Text("Use a unique folder name (up to ${OrganizationLimits.MAX_NAME_CODE_POINTS} characters).",
                    color = MaterialTheme.colorScheme.error)
                FolderIconPicker(draft.iconId) { iconId -> draft = draft.copy(iconId = iconId) }
                Text("Folder order", style = MaterialTheme.typography.titleSmall)
                OrganizationChoiceRow("Alphabetical", draft.placement == FolderPlacement.APP_LIST,
                    tag = "folder-editor-placement:list") {
                    if (it) draft = draft.copy(placement = FolderPlacement.APP_LIST)
                }
                OrganizationChoiceRow("Pinned (manual order)", draft.placement == FolderPlacement.MOST_USED,
                    tag = "folder-editor-placement:most-used") {
                    if (it) draft = draft.copy(placement = FolderPlacement.MOST_USED)
                }
                Text(if (mostUsedEnabled) "Both appear in the App drawer Folders section, below Most Used."
                    else "Both appear in the App drawer Folders section, above alphabetical apps.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Apps in this folder", style = MaterialTheme.typography.titleSmall)
                if (draft.members.isEmpty()) Text("No apps added yet.")
                draft.members.forEachIndexed { index, key ->
                    val app = byKey[key]
                    Column {
                        Text(app?.label ?: key.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (key in hiddenKeys) Text("Hidden app", style = MaterialTheme.typography.bodySmall)
                        else if (app == null || !app.enabled) Text("Unavailable · ${key.activityName} · profile ${key.profileSerial}",
                            style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            TextButton(enabled = index > 0, onClick = {
                                draft = draft.copy(members = draft.members.toMutableList().apply {
                                    add(index - 1, removeAt(index)) })
                            }) { Text("Up") }
                            TextButton(enabled = index < draft.members.lastIndex, onClick = {
                                draft = draft.copy(members = draft.members.toMutableList().apply {
                                    add(index + 1, removeAt(index)) })
                            }) { Text("Down") }
                            TextButton(onClick = { draft = draft.copy(members = draft.members - key) }) { Text("Remove") }
                        }
                    }
                }
                TextButton(onClick = { pickerOpen = !pickerOpen }) { Text(if (pickerOpen) "Close app picker" else "Add apps") }
                if (pickerOpen) {
                    OutlinedTextField(query, { query = it }, label = { Text("Find an app") },
                        modifier = Modifier.fillMaxWidth())
                    val choices = catalog.filter { it.key !in draft.members && it.key !in hiddenKeys &&
                        (query.isBlank() || it.label.contains(query, ignoreCase = true) ||
                            it.key.packageName.contains(query, ignoreCase = true)) }
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
                    if (choices.isEmpty()) Text("No matching apps.")
                    choices.take(80).forEach { app -> TextButton(onClick = {
                        draft = draft.copy(members = draft.members + app.key)
                    }) { Text("${app.label} · ${app.key.packageName}", maxLines = 1,
                        overflow = TextOverflow.Ellipsis) } }
                    if (choices.size > 80) Text("Refine your search to see more apps.",
                        style = MaterialTheme.typography.bodySmall)
                }
                smartSettings(draft, conditions, { draft = it }, { conditions = it })
                if (invalidSmart) Text("Add at least one enabled condition before using Smart visibility.",
                    color = MaterialTheme.colorScheme.error)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("folder-save-error")) }
                if (onDelete != null) TextButton(enabled = !busy, onClick = { deleteConfirm = true }) {
                    Text("Delete folder", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(enabled = !busy && !invalidName && !invalidSmart,
            onClick = { submit { onSave(draft.copy(name = normalized), conditions) } },
            modifier = Modifier.testTag("folder-save")) { Text(if (error == null) "Save" else "Retry") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
    if (deleteConfirm && onDelete != null) AlertDialog(
        onDismissRequest = { deleteConfirm = false }, title = { Text("Delete ${folder.name}?") },
        text = { Text("The folder shortcut and collection will be removed. Its apps stay installed.") },
        confirmButton = { TextButton(enabled = !busy, onClick = { submit(onDelete) }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("Cancel") } },
    )
}
