package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.R
import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp

@Composable
fun HomeScreen(
    catalog: AppCatalog,
    onLaunch: (LaunchableApp) -> Unit,
    onRefresh: () -> Unit,
    onMakeDefault: () -> Unit,
    onOpenSettings: () -> Unit,
    showMakeDefault: Boolean = true,
    listState: LazyListState = rememberLazyListState(),
    orderedApps: List<LaunchableApp>? = null,
    searchQuery: String? = null,
    onSearchQueryChange: ((String) -> Unit)? = null,
) {
    val locale = LocalConfiguration.current.locales[0]
    val apps = remember(catalog.apps, orderedApps, locale) {
        homeOrderedApps(catalog.apps, orderedApps, locale)
    }
    var localQuery by rememberSaveable { mutableStateOf("") }
    val query = searchQuery ?: localQuery
    val updateQuery: (String) -> Unit = { value ->
        if (searchQuery == null) localQuery = value else onSearchQueryChange?.invoke(value)
    }
    val visibleApps = remember(apps, query, locale) { filterHomeApps(apps, query, locale) }
    var lastShownQuery by remember { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (query != lastShownQuery) {
            listState.scrollToItem(0)
            lastShownQuery = query
        }
    }

    var editing by rememberSaveable { mutableStateOf(false) }
    val expanded = editing || query.isNotEmpty()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun endEditing() {
        editing = false
        keyboard?.hide()
        focusManager.clearFocus()
    }

    LaunchedEffect(editing) {
        if (editing) focusRequester.requestFocus()
    }
    BackHandler(enabled = editing) { endEditing() }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (expanded) {
                    Surface(
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(R.drawable.ic_home_search),
                                contentDescription = null,
                                modifier = Modifier.padding(start = 12.dp).size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            BasicTextField(
                                value = query,
                                onValueChange = updateQuery,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 8.dp)
                                    .focusRequester(focusRequester)
                                    .onFocusChanged { if (it.isFocused) editing = true }
                                    .semantics { contentDescription = "Search apps" }
                                    .testTag("home-search-field"),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { endEditing() }),
                                decorationBox = { innerTextField ->
                                    Box(contentAlignment = Alignment.CenterStart) {
                                        if (query.isEmpty()) {
                                            Text(
                                                "Search apps",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                style = MaterialTheme.typography.bodyLarge,
                                            )
                                        }
                                        innerTextField()
                                    }
                                },
                            )
                            if (query.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        updateQuery("")
                                        editing = true
                                        focusRequester.requestFocus()
                                    },
                                    modifier = Modifier.size(48.dp).testTag("home-search-clear")
                                        .semantics { contentDescription = "Clear search" },
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_home_clear),
                                        contentDescription = null,
                                    )
                                }
                            } else {
                                Spacer(Modifier.size(12.dp))
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                    Surface(
                        onClick = { editing = true },
                        modifier = Modifier.widthIn(min = 112.dp).heightIn(min = 48.dp).testTag("home-search")
                            .semantics { contentDescription = "Search" },
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_home_search),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Text("Search", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                IconButton(
                    onClick = {
                        endEditing()
                        onOpenSettings()
                    },
                    modifier = Modifier.size(48.dp).testTag("home-settings")
                        .semantics { contentDescription = "Settings" },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_home_settings),
                        contentDescription = null,
                    )
                }
            }
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (catalog.lastError != null) {
                item(key = "catalog-error") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        Text("Apps could not be loaded", style = MaterialTheme.typography.titleMedium)
                        Text(catalog.lastError, color = MaterialTheme.colorScheme.error)
                        Button(
                            modifier = Modifier
                                .semantics { contentDescription = "Retry loading apps" }
                                .testTag("home-retry"),
                            onClick = onRefresh,
                        ) { Text("Retry") }
                    }
                }
            }
            if (catalog.isRefreshing && apps.isEmpty() && catalog.lastError == null) {
                item(key = "catalog-loading") {
                    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text("Loading apps…", modifier = Modifier.padding(top = 12.dp))
                    }
                }
            } else if (apps.isEmpty() && catalog.lastError == null) {
                item(key = "catalog-empty") {
                    Text("No apps available", modifier = Modifier.fillMaxWidth().padding(24.dp))
                }
            } else if (visibleApps.isEmpty() && query.isNotBlank() && catalog.lastError == null) {
                item(key = "search-no-results") {
                    Text("No matching apps", modifier = Modifier.fillMaxWidth().padding(24.dp))
                }
            }
            items(visibleApps, key = { it.key.stableId }) { app ->
                val quiet = catalog.profiles.firstOrNull { it.serialNumber == app.key.profileSerial }?.quietModeEnabled == true
                HomeAppRow(
                    app = app,
                    enabled = app.enabled && !quiet,
                    onClick = {
                        endEditing()
                        onLaunch(app)
                    },
                )
            }
            item(key = "home-actions") {
                Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (catalog.isRefreshing && apps.isNotEmpty()) {
                        Text("Updating apps…", style = MaterialTheme.typography.bodySmall)
                    }
                    if (showMakeDefault) Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Make Modular the default launcher" }
                            .testTag("home-make-default"),
                        onClick = onMakeDefault,
                    ) { Text("Make default") }
                }
            }
        }
    }
}
