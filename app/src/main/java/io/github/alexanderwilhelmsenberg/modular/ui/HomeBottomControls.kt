package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.R

@Composable
internal fun HomeBottomControls(
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
    onEditingChange: (Boolean) -> Unit,
    onSearchAction: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.weight(1f).height(48.dp).testTag("home-search"),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = RoundedCornerShape(12.dp),
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().height(48.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { onEditingChange(it.isFocused) }
                    .semantics { contentDescription = "Search apps" }
                    .testTag("home-search-field"),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = onSearchAction),
                decorationBox = { inner ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_home_search), null, Modifier.padding(start = 12.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(Modifier.weight(1f).padding(start = 8.dp, top = 4.dp, bottom = 4.dp), contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) Text("Search apps", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            inner()
                        }
                        if (query.isNotEmpty()) IconButton(
                            onClick = { onQueryChange(""); focusRequester.requestFocus() },
                            modifier = Modifier.size(48.dp).testTag("home-search-clear").semantics { contentDescription = "Clear search" },
                        ) { Icon(painterResource(R.drawable.ic_home_clear), null) }
                        else Spacer(Modifier.size(48.dp))
                    }
                },
            )
        }
        IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp).testTag("home-settings").semantics { contentDescription = "Settings" }) {
            Icon(painterResource(R.drawable.ic_home_settings), null)
        }
    }
}
