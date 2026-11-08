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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.organization.CategoryDefinition
import io.github.alexanderwilhelmsenberg.modular.organization.FolderDefinition
import io.github.alexanderwilhelmsenberg.modular.organization.FolderPlacement
import io.github.alexanderwilhelmsenberg.modular.organization.HomePageId
import io.github.alexanderwilhelmsenberg.modular.organization.MembershipOverride
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationBatch
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationEdit
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationLimits
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationSnapshot
import java.text.Collator
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

/** Edits in this sheet are local until Done commits one OrganizationBatch. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppOrganizationPicker(
    app: LaunchableApp,
    snapshot: OrganizationSnapshot,
    mostUsedEnabled: Boolean,
    categoryMembership: (String, AppKey) -> Boolean,
    onUpdate: suspend (OrganizationBatch) -> Result<OrganizationSnapshot>,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var baseline by remember(app.key) { mutableStateOf(snapshot) }
    var originalCategories by remember(app.key) {
        mutableStateOf(snapshot.categories.filter { categoryMembership(it.id, app.key) }.mapTo(mutableSetOf()) { it.id }.toSet())
    }
    var originalFolders by remember(app.key) {
        mutableStateOf(snapshot.folders.filter { app.key in it.members }.mapTo(mutableSetOf()) { it.id }.toSet())
    }
    var selectedCategories by remember(app.key) { mutableStateOf(originalCategories.toSet()) }
    var selectedFolders by remember(app.key) { mutableStateOf(originalFolders.toSet()) }
    var touchedCategories by remember(app.key) { mutableStateOf(emptySet<String>()) }
    var touchedFolders by remember(app.key) { mutableStateOf(emptySet<String>()) }
    var resetToAuto by remember(app.key) { mutableStateOf(emptySet<String>()) }
    var newCategories by remember(app.key) { mutableStateOf(emptyList<CategoryDefinition>()) }
    var newFolders by remember(app.key) { mutableStateOf(emptyList<FolderDefinition>()) }
    var createType by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refreshDraft() {
        val currentCategories = snapshot.categories.mapTo(HashSet()) { it.id }
        val currentFolders = snapshot.folders.mapTo(HashSet()) { it.id }
        val deleted = (baseline.categories.map { it.id }.toSet() - currentCategories) +
            (baseline.folders.map { it.id }.toSet() - currentFolders)
        val currentCategorySelections = snapshot.categories.filter { categoryMembership(it.id, app.key) }
            .mapTo(HashSet()) { it.id }
        val currentFolderSelections = snapshot.folders.filter { app.key in it.members }
            .mapTo(HashSet()) { it.id }
        selectedCategories = currentCategories.filterTo(HashSet()) { id ->
            if (id in touchedCategories) id in selectedCategories else id in currentCategorySelections
        } + newCategories.map { it.id }
        selectedFolders = currentFolders.filterTo(HashSet()) { id ->
            if (id in touchedFolders) id in selectedFolders else id in currentFolderSelections
        } + newFolders.map { it.id }
        originalCategories = currentCategorySelections
        originalFolders = currentFolderSelections
        touchedCategories = touchedCategories.intersect(currentCategories)
        touchedFolders = touchedFolders.intersect(currentFolders)
        resetToAuto = resetToAuto.intersect(currentCategories)
        baseline = snapshot
        error = if (deleted.isNotEmpty()) "Some destinations were deleted. Review your selections before saving." else null
    }

    fun save() {
        if (busy) return
        if (snapshot.revision != baseline.revision) { refreshDraft(); return }
        val edits = buildList {
            newCategories.forEachIndexed { index, category ->
                add(OrganizationEdit.CreateCategory(category, baseline.pageOrder.indexOf(HomePageId.Home) + 1 + index))
                add(OrganizationEdit.SetMembership(category.id, app.key, MembershipOverride.INCLUDE))
            }
            newFolders.forEach { add(OrganizationEdit.CreateFolder(it)) }
            baseline.categories.forEach { category ->
                val selected = category.id in selectedCategories
                val wasSelected = category.id in originalCategories
                when {
                    category.id in resetToAuto -> add(OrganizationEdit.SetMembership(category.id, app.key, MembershipOverride.AUTO))
                    selected && (!wasSelected || category.id in touchedCategories &&
                        baseline.overrideFor(category.id, app.key) == MembershipOverride.AUTO) ->
                        add(OrganizationEdit.SetMembership(category.id, app.key, MembershipOverride.INCLUDE))
                    !selected && wasSelected -> add(OrganizationEdit.SetMembership(category.id, app.key, MembershipOverride.EXCLUDE))
                }
            }
            baseline.folders.forEach { folder ->
                val selected = folder.id in selectedFolders
                val wasSelected = folder.id in originalFolders
                if (selected != wasSelected) add(OrganizationEdit.UpdateFolder(folder.copy(
                    members = if (selected) folder.members + app.key else folder.members - app.key,
                )))
            }
        }
        if (edits.isEmpty()) { onDismiss(); return }
        busy = true
        error = null
        scope.launch {
            val result = runCatching { onUpdate(OrganizationBatch(edits, baseline.revision)) }
                .getOrElse { Result.failure(it) }
            busy = false
            result.onSuccess { onDismiss() }.onFailure {
                error = it.message ?: "Could not save organization. Review selections and retry."
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!busy && createType == null) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("app-organization-picker"),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text("Add to category or folder", style = MaterialTheme.typography.titleLarge)
            Text(app.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!snapshot.categoriesEnabled) Text(
                "Category assignments are saved. Turn on category pages in Settings to browse them.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Categories", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp))
            (baseline.categories + newCategories).forEach { category ->
                val staged = newCategories.any { it.id == category.id }
                OrganizationChoiceRow(
                    label = "${category.name} · Category" + if (!category.enabled) " · page off" else "",
                    checked = category.id in selectedCategories || staged,
                    enabled = !busy && !staged,
                    tag = "organization-category:${category.id}",
                    onChange = { checked ->
                        selectedCategories = if (checked) selectedCategories + category.id else selectedCategories - category.id
                        touchedCategories = touchedCategories + category.id
                        resetToAuto = resetToAuto - category.id
                    },
                )
                if (staged) TextButton(enabled = !busy, onClick = {
                    newCategories = newCategories.filterNot { it.id == category.id }
                    selectedCategories = selectedCategories - category.id
                }) { Text("Remove draft ${category.name}") }
                if (!staged && baseline.overrideFor(category.id, app.key) != MembershipOverride.AUTO) {
                    TextButton(enabled = !busy, onClick = {
                        resetToAuto = resetToAuto + category.id
                        touchedCategories = touchedCategories + category.id
                        selectedCategories = if (categoryMembership(category.id, app.key)) selectedCategories + category.id
                            else selectedCategories - category.id
                    }) { Text(if (category.id in resetToAuto) "Reset to automatic on Done" else "Reset to automatic") }
                }
            }
            TextButton(enabled = !busy, onClick = { createType = "category" }) { Text("Create category") }
            HorizontalDivider()
            Text("Folders", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            (baseline.folders + newFolders).forEach { folder ->
                val staged = newFolders.any { it.id == folder.id }
                OrganizationChoiceRow(
                    label = "${folder.name} · Folder" + if (staged) " · new" else "",
                    checked = folder.id in selectedFolders || staged,
                    enabled = !busy && !staged,
                    tag = "organization-folder:${folder.id}",
                    onChange = { checked ->
                        selectedFolders = if (checked) selectedFolders + folder.id else selectedFolders - folder.id
                        touchedFolders = touchedFolders + folder.id
                    },
                )
                if (staged) TextButton(enabled = !busy, onClick = {
                    newFolders = newFolders.filterNot { it.id == folder.id }
                    selectedFolders = selectedFolders - folder.id
                }) { Text("Remove draft ${folder.name}") }
            }
            TextButton(enabled = !busy, onClick = { createType = "folder" }) { Text("Create folder") }
            if (snapshot.revision != baseline.revision) Text(
                "Organization changed while this picker was open. Refresh to review current destinations; your draft selections will be kept.",
                color = MaterialTheme.colorScheme.error,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("organization-save-error")) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
                TextButton(enabled = !busy, onClick = ::save, modifier = Modifier.testTag("organization-done")) {
                    Text(if (snapshot.revision != baseline.revision) "Refresh" else if (error == null) "Done" else "Retry")
                }
            }
        }
    }
    when (createType) {
        "category" -> CreateOrganizationDestinationDialog(
            type = "category", names = baseline.categories.map { it.name } + newCategories.map { it.name },
            mostUsedEnabled = mostUsedEnabled, onDismiss = { createType = null },
            onCreate = { name, _, enabled, showOnHome ->
                val category = CategoryDefinition(id = UUID.randomUUID().toString(), name = name,
                    enabled = enabled, showOnHome = showOnHome)
                newCategories = newCategories + category
                selectedCategories = selectedCategories + category.id
                createType = null
            },
        )
        "folder" -> CreateOrganizationDestinationDialog(
            type = "folder", names = baseline.folders.map { it.name } + newFolders.map { it.name },
            mostUsedEnabled = mostUsedEnabled, onDismiss = { createType = null },
            onCreate = { name, placement, _, _ ->
                val folder = FolderDefinition(id = UUID.randomUUID().toString(), name = name,
                    members = listOf(app.key), placement = placement)
                newFolders = newFolders + folder
                selectedFolders = selectedFolders + folder.id
                createType = null
            },
        )
    }
}

@Composable
internal fun OrganizationChoiceRow(label: String, checked: Boolean, enabled: Boolean = true,
    tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun CreateOrganizationDestinationDialog(
    type: String,
    names: List<String>,
    mostUsedEnabled: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String, FolderPlacement, Boolean, Boolean) -> Unit,
) {
    var name by remember(type) { mutableStateOf("") }
    var placement by remember(type) { mutableStateOf(FolderPlacement.APP_LIST) }
    var enabled by remember(type) { mutableStateOf(false) }
    var showOnHome by remember(type) { mutableStateOf(true) }
    val normalized = name.trim()
    val invalid = organizationNameInvalid(normalized, names)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (type == "folder") "Create folder" else "Create category") },
        text = {
            Column {
                OutlinedTextField(name, { if (it.codePointCount(0, it.length) <= OrganizationLimits.MAX_NAME_CODE_POINTS) name = it }, label = { Text("Name") },
                    singleLine = true, isError = name.isNotBlank() && invalid,
                    modifier = Modifier.fillMaxWidth().testTag("organization-create-name"))
                if (name.isNotBlank() && invalid) Text("Use a unique name (up to ${OrganizationLimits.MAX_NAME_CODE_POINTS} characters).",
                    color = MaterialTheme.colorScheme.error)
                if (type == "folder") {
                    OrganizationChoiceRow("App list", placement == FolderPlacement.APP_LIST, tag = "folder-placement:list") {
                        if (it) placement = FolderPlacement.APP_LIST
                    }
                    OrganizationChoiceRow("Most Used", placement == FolderPlacement.MOST_USED,
                        enabled = mostUsedEnabled, tag = "folder-placement:most-used") {
                        if (it) placement = FolderPlacement.MOST_USED
                    }
                    if (!mostUsedEnabled) Text("Turn on Most Used in Settings to place a folder there.",
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    OrganizationChoiceRow("Enable page", enabled, tag = "category-create-enabled") { enabled = it }
                    OrganizationChoiceRow("Show member apps on Home", showOnHome, tag = "category-create-home") { showOnHome = it }
                    Text("New page goes immediately right of Home. You can move it in Settings.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(enabled = !invalid, onClick = { onCreate(normalized, placement, enabled, showOnHome) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun organizationNameInvalid(name: String, otherNames: List<String>): Boolean {
    if (name.isEmpty() || name.codePointCount(0, name.length) > OrganizationLimits.MAX_NAME_CODE_POINTS ||
        name.any { it.isISOControl() }) return true
    val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
    return otherNames.any { collator.compare(it, name) == 0 }
}
