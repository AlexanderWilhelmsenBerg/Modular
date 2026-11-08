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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.organization.CategoryDefinition
import io.github.alexanderwilhelmsenberg.modular.organization.CategoryClassifier
import io.github.alexanderwilhelmsenberg.modular.organization.HomePageId
import io.github.alexanderwilhelmsenberg.modular.organization.HomePinRef
import io.github.alexanderwilhelmsenberg.modular.organization.MembershipOverride
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationBatch
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationEdit
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationSnapshot
import java.text.Collator
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

/** Settings entry point for Core organization. */
@Composable
fun CategorySettingsUi(
    snapshot: OrganizationSnapshot,
    catalog: List<LaunchableApp>,
    onUpdate: suspend (OrganizationBatch) -> Result<OrganizationSnapshot>,
    onManageFolders: () -> Unit,
    onStartMegaSorter: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var detailsOpen by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var memberId by remember { mutableStateOf<String?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var confirmSuppression by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val automaticMembers = remember(catalog, snapshot.categories) {
        snapshot.categories.associate { category ->
            category.id to catalog.filter { CategoryClassifier.matches(category.id, it) }.map { it.key }.toSet()
        }
    }

    fun save(edits: List<OrganizationEdit>, afterSuccess: () -> Unit = {}) {
        if (busy || edits.isEmpty()) return
        busy = true
        error = null
        scope.launch {
            val result = runCatching { onUpdate(OrganizationBatch(edits, expectedRevision = snapshot.revision)) }
                .getOrElse { Result.failure(it) }
            busy = false
            result.onSuccess { afterSuccess() }
                .onFailure { error = it.message ?: "Could not save category settings. Try again." }
        }
    }

    fun isMember(category: CategoryDefinition, key: AppKey): Boolean = when (snapshot.overrideFor(category.id, key)) {
        MembershipOverride.INCLUDE -> true
        MembershipOverride.EXCLUDE -> false
        MembershipOverride.AUTO -> category.automatic && key in automaticMembers[category.id].orEmpty()
    }

    Card(Modifier.fillMaxWidth().testTag("core-categories-card")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Categories and folders", style = MaterialTheme.typography.titleMedium)
                    Text("Optional pages for organizing apps", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = snapshot.categoriesEnabled,
                    enabled = !busy,
                    onCheckedChange = { save(listOf(OrganizationEdit.SetCategoriesEnabled(it))) },
                    modifier = Modifier.semantics { contentDescription = "Categories" }.testTag("categories-master-toggle"),
                )
            }
            OutlinedButton(onClick = { detailsOpen = true }, modifier = Modifier.testTag("categories-configure")) {
                Text("Configure categories")
            }
            if (!detailsOpen) error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    if (detailsOpen) AlertDialog(
        onDismissRequest = { if (!busy) detailsOpen = false },
        title = { Text("Categories and folders") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Category pages are optional. Turning them off keeps names, members, and page order.", style = MaterialTheme.typography.bodySmall)
                snapshot.categories.forEach { category ->
                    Card(Modifier.fillMaxWidth().testTag("category:${category.id}")) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(category.name, style = MaterialTheme.typography.titleMedium)
                            CategorySettingSwitch("Page enabled", category.enabled, !busy, "category-enabled:${category.id}") {
                                save(listOf(OrganizationEdit.UpdateCategory(category.copy(enabled = it))))
                            }
                            CategorySettingSwitch("Show apps on Home", category.showOnHome, !busy, "category-home:${category.id}") { show ->
                                if (!show && category.enabled && snapshot.categoriesEnabled) confirmSuppression = category.id
                                else save(listOf(OrganizationEdit.UpdateCategory(category.copy(showOnHome = show))))
                            }
                            if (category.builtIn && category.id in setOf("games", "media", "social", "productivity")) {
                                CategorySettingSwitch("Automatic matching", category.automatic, !busy, "category-auto:${category.id}") {
                                    save(listOf(OrganizationEdit.UpdateCategory(category.copy(automatic = it))))
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { editingId = category.id }, enabled = !busy) { Text("Rename") }
                                TextButton(onClick = { memberId = category.id }, enabled = !busy) { Text("Members") }
                                if (!category.builtIn) TextButton(onClick = { deleteId = category.id }, enabled = !busy) { Text("Delete") }
                            }
                        }
                    }
                }
                OutlinedButton(onClick = { creating = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("category-create")) { Text("Create category") }
                CategoryPageOrder(snapshot.pageOrder, snapshot.categories, onReorder = {
                    save(listOf(OrganizationEdit.SetPageOrder(it)))
                }, enabled = !busy)
                OutlinedButton(onClick = { detailsOpen = false; onManageFolders() }, modifier = Modifier.fillMaxWidth().testTag("categories-manage-folders")) { Text("Manage folders") }
                OutlinedButton(onClick = { detailsOpen = false; onStartMegaSorter() }, modifier = Modifier.fillMaxWidth().testTag("categories-start-sorter")) { Text("Start Mega Sorter") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { detailsOpen = false }, enabled = !busy) { Text("Done") } },
    )

    val edited = snapshot.categories.firstOrNull { it.id == editingId }
    if (creating || edited != null) CategoryEditorDialog(
        category = edited,
        existing = snapshot.categories,
        onDismiss = { creating = false; editingId = null },
        onSave = { name, enabled, showOnHome, leftOfHome ->
            if (edited == null) {
                val id = UUID.randomUUID().toString()
                val position = snapshot.pageOrder.indexOf(HomePageId.Home).coerceAtLeast(0) + if (leftOfHome) 0 else 1
                save(listOf(OrganizationEdit.CreateCategory(CategoryDefinition(id, name, enabled, showOnHome), position))) {
                    creating = false
                }
            } else save(listOf(OrganizationEdit.UpdateCategory(edited.copy(name = name, enabled = enabled, showOnHome = showOnHome)))) {
                editingId = null
            }
        },
        busy = busy,
        saveError = error,
    )

    snapshot.categories.firstOrNull { it.id == memberId }?.let { category ->
        CategoryMembersDialog(
            category = category,
            snapshot = snapshot,
            catalog = catalog,
            automaticMatches = automaticMembers[category.id].orEmpty(),
            busy = busy,
            error = error,
            onDismiss = { memberId = null },
            onOverride = { key, value -> save(listOf(OrganizationEdit.SetMembership(category.id, key, value))) },
            onReset = {
                save(listOf(OrganizationEdit.ResetCategoryOverrides(category.id)))
            },
        )
    }

    snapshot.categories.firstOrNull { it.id == deleteId }?.let { category ->
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text("Delete ${category.name}?") },
            text = { Text("This removes its page and membership choices. Apps and folders stay installed.") },
            confirmButton = { Button(enabled = !busy, onClick = {
                save(listOf(OrganizationEdit.DeleteCategory(category.id))) { deleteId = null }
            }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Cancel") } },
        )
    }

    snapshot.categories.firstOrNull { it.id == confirmSuppression }?.let { category ->
        val affected = catalog.filter { app -> isMember(category, app.key) && snapshot.categories.none { other ->
            other.id != category.id && other.enabled && !other.showOnHome && isMember(other, app.key)
        } }.map { it.key }.toSet()
        val pins = snapshot.pins.count { it is HomePinRef.App && it.key in affected }
        AlertDialog(
            onDismissRequest = { confirmSuppression = null },
            title = { Text("Hide ${category.name} apps from Home?") },
            text = { Text("${affected.size} direct app entries, including $pins pins, will leave Home. Search and the category page will still show them. Saved pins return if you show these apps on Home again.") },
            confirmButton = { Button(enabled = !busy, onClick = {
                save(listOf(OrganizationEdit.UpdateCategory(category.copy(showOnHome = false)))) { confirmSuppression = null }
            }) { Text("Hide from Home") } },
            dismissButton = { TextButton(onClick = { confirmSuppression = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CategorySettingSwitch(label: String, checked: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f).padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = label }.testTag(tag))
    }
}

internal fun categoryNameError(name: String, existing: List<CategoryDefinition>, ownId: String?): String? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "Enter a category name."
    if (trimmed.codePointCount(0, trimmed.length) > 60) return "Use at most 60 characters."
    val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
    if (existing.any { it.id != ownId && collator.compare(it.name.trim(), trimmed) == 0 }) return "A category already has this name."
    return null
}

@Composable
private fun CategoryEditorDialog(
    category: CategoryDefinition?,
    existing: List<CategoryDefinition>,
    onDismiss: () -> Unit,
    onSave: (String, Boolean, Boolean, Boolean) -> Unit,
    busy: Boolean,
    saveError: String?,
) {
    var name by remember(category?.id) { mutableStateOf(category?.name.orEmpty()) }
    var enabled by remember(category?.id) { mutableStateOf(category?.enabled ?: true) }
    var showOnHome by remember(category?.id) { mutableStateOf(category?.showOnHome ?: true) }
    var leftOfHome by remember(category?.id) { mutableStateOf(false) }
    val validation = categoryNameError(name, existing, category?.id)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (category == null) "Create category" else "Edit category") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, onValueChange = { name = it }, label = { Text("Name") }, isError = validation != null && name.isNotEmpty(), singleLine = true, modifier = Modifier.fillMaxWidth().testTag("category-editor-name"))
                validation?.let { if (name.isNotEmpty()) Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (category == null) {
                    CategorySettingSwitch("Page enabled", enabled, !busy, "category-editor-enabled") { enabled = it }
                    CategorySettingSwitch("Show apps on Home", showOnHome, !busy, "category-editor-home") { showOnHome = it }
                    Text("Initial position", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { leftOfHome = true }) { Text(if (leftOfHome) "✓ Left of Home" else "Left of Home") }
                        TextButton(onClick = { leftOfHome = false }) { Text(if (!leftOfHome) "✓ Right of Home" else "Right of Home") }
                    }
                }
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { Button(enabled = !busy && validation == null, onClick = { onSave(name.trim(), enabled, showOnHome, leftOfHome) }) { Text("Save") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Opens the same exact-app member controls from an empty category page. */
@Composable
fun CategoryMembersDialog(
    categoryId: String,
    snapshot: OrganizationSnapshot,
    catalog: List<LaunchableApp>,
    onUpdate: suspend (OrganizationBatch) -> Result<OrganizationSnapshot>,
    onDismiss: () -> Unit,
) {
    val category = snapshot.categories.firstOrNull { it.id == categoryId } ?: return
    val scope = rememberCoroutineScope()
    var busy by remember(categoryId) { mutableStateOf(false) }
    var error by remember(categoryId) { mutableStateOf<String?>(null) }
    fun save(edit: OrganizationEdit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            val result = runCatching { onUpdate(OrganizationBatch(listOf(edit), snapshot.revision)) }
                .getOrElse { Result.failure(it) }
            busy = false
            result.onFailure { error = it.message ?: "Could not save member choice. Try again." }
        }
    }
    CategoryMembersDialog(
        category = category,
        snapshot = snapshot,
        catalog = catalog,
        automaticMatches = catalog.filter { CategoryClassifier.matches(category.id, it) }.map { it.key }.toSet(),
        busy = busy,
        error = error,
        onDismiss = onDismiss,
        onOverride = { key, value -> save(OrganizationEdit.SetMembership(category.id, key, value)) },
        onReset = { save(OrganizationEdit.ResetCategoryOverrides(category.id)) },
    )
}

@Composable
private fun CategoryMembersDialog(
    category: CategoryDefinition,
    snapshot: OrganizationSnapshot,
    catalog: List<LaunchableApp>,
    automaticMatches: Set<AppKey>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onOverride: (AppKey, MembershipOverride) -> Unit,
    onReset: () -> Unit,
) {
    var query by remember(category.id) { mutableStateOf("") }
    val sorted = remember(catalog, query) {
        val collator = Collator.getInstance(Locale.getDefault())
        catalog.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) || it.key.packageName.contains(query, ignoreCase = true) }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
    }
    val unavailable = snapshot.overrides.keys.filter { key -> key.categoryId == category.id && catalog.none { it.key == key.appKey } }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("${category.name} members") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 490.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Include and Exclude override Android matching. Auto follows it when automatic matching is on.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(query, onValueChange = { query = it }, label = { Text("Find an app") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("category-member-search"))
                sorted.forEach { app ->
                    val value = snapshot.overrideFor(category.id, app.key)
                    val automatic = category.automatic && app.key in automaticMatches
                    val provenance = when {
                        value == MembershipOverride.INCLUDE -> "Added by you"
                        value == MembershipOverride.EXCLUDE -> "Excluded by you"
                        automatic -> "Android category"
                        else -> "Not in category"
                    }
                    var menu by remember(app.key) { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f).padding(top = 4.dp)) {
                            Text(app.label, style = MaterialTheme.typography.bodyMedium)
                            Text(provenance, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { menu = true }, enabled = !busy, modifier = Modifier.testTag("category-member:${app.key.stableId}")) {
                            Text(value.name.lowercase().replaceFirstChar { it.uppercase() })
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            MembershipOverride.entries.forEach { choice ->
                                DropdownMenuItem(text = { Text(choice.name.lowercase().replaceFirstChar { it.uppercase() }) }, onClick = {
                                    menu = false
                                    onOverride(app.key, choice)
                                })
                            }
                        }
                    }
                }
                unavailable.forEach { entry ->
                    var menu by remember(entry.appKey) { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${entry.appKey.packageName} (unavailable)", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = !busy, onClick = { menu = true }) { Text(snapshot.overrideFor(category.id, entry.appKey).name.lowercase()) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            MembershipOverride.entries.forEach { choice ->
                                DropdownMenuItem(text = { Text(choice.name.lowercase().replaceFirstChar { it.uppercase() }) }, onClick = {
                                    menu = false
                                    onOverride(entry.appKey, choice)
                                })
                            }
                        }
                    }
                }
                TextButton(enabled = !busy && snapshot.overrides.keys.any { it.categoryId == category.id }, onClick = onReset) { Text("Reset manual choices to Auto") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Done") } },
    )
}
