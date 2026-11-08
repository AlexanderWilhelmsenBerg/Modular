package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.organization.CategoryDefinition

/** Explicit physical direction picker. Disabled category pages are valid destinations. */
@Composable
fun MegaSorterSetup(
    categories: List<CategoryDefinition>,
    onCreateCategory: (String) -> Unit,
    onStart: (leftId: String?, rightId: String?) -> Unit,
    onDismiss: () -> Unit,
    creationError: String? = null,
) {
    var leftId by remember { mutableStateOf<String?>(null) }
    var rightId by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    val trimmedName = newName.trim()
    val duplicateName = categories.any { it.name.trim().equals(trimmedName, ignoreCase = true) }
    val longName = newName.codePointCount(0, newName.length) > 100
    val nameError = when {
        duplicateName && trimmedName.isNotEmpty() -> "Category name already exists"
        longName -> "Use 100 characters or fewer"
        else -> creationError
    }
    val available = categories.map { it.id }.toSet()
    LaunchedEffect(available) {
        if (leftId != null && leftId !in available) leftId = null
        if (rightId != null && rightId !in available) rightId = null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mega Sorter") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Choose one or two categories. Swipe right for blue, left for green. Category pages can stay off while you sort.")
                Text("Right · blue →")
                SorterDestinationChoices(categories, rightId) { selected ->
                    rightId = selected
                    if (leftId == selected) leftId = null
                }
                Text("← Left · green")
                SorterDestinationChoices(categories, leftId) { selected ->
                    leftId = selected
                    if (rightId == selected) rightId = null
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New category") },
                    isError = nameError != null,
                    supportingText = if (nameError == null) null else { { Text(nameError) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    enabled = trimmedName.isNotEmpty() && nameError == null,
                    onClick = { onCreateCategory(trimmedName); newName = "" },
                ) { Text("Create category") }
            }
        },
        confirmButton = {
            Button(
                enabled = leftId != null || rightId != null,
                onClick = { onStart(leftId, rightId) },
            ) { Text("Start sorting") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SorterDestinationChoices(
    categories: List<CategoryDefinition>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onSelect(null) }.padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selectedId == null, onClick = { onSelect(null) })
        Text("Unused")
    }
    categories.forEach { category ->
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onSelect(category.id) }.padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selectedId == category.id, onClick = { onSelect(category.id) })
            Column {
                Text(category.name)
                if (!category.enabled) Text("Page off")
            }
        }
    }
}
