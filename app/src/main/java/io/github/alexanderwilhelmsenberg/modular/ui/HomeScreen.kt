package io.github.alexanderwilhelmsenberg.modular.ui

import android.content.pm.LauncherApps
import android.os.UserManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import java.text.Collator
import androidx.compose.ui.platform.LocalConfiguration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    catalog: AppCatalog,
    onLaunch: (LaunchableApp) -> Unit,
    onRefresh: () -> Unit,
    onMakeDefault: () -> Unit,
    onOpenSettings: () -> Unit,
    showMakeDefault: Boolean = true,
    listState: LazyListState = rememberLazyListState(),
) {
    val context = LocalContext.current
    val userManager = remember(context) { context.getSystemService(UserManager::class.java) }
    val launcherApps = remember(context) { context.getSystemService(LauncherApps::class.java) }
    val primarySerial = remember(userManager) {
        runCatching { userManager?.getSerialNumberForUser(android.os.Process.myUserHandle()) }
            .getOrNull()
    }
    val profiles = remember(catalog.profiles, primarySerial) {
        catalog.profiles.associate { profile ->
            profile.serialNumber to when {
                profile.quietModeEnabled -> "Work profile · paused"
                profile.serialNumber == primarySerial -> "Personal profile"
                else -> "Profile ${profile.serialNumber}"
            }
        }
    }
    val locale = LocalConfiguration.current.locales[0]
    val sortedApps = remember(catalog.apps, locale) {
        val collator = Collator.getInstance(locale)
        collator.strength = Collator.PRIMARY
        catalog.apps.sortedWith { left, right ->
            val byLabel = collator.compare(left.label, right.label)
            if (byLabel != 0) byLabel else left.key.stableId.compareTo(right.key.stableId)
        }
    }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Apps") },
                    actions = {
                        TextButton(
                            modifier = Modifier
                                .semantics { contentDescription = "Open settings" }
                                .testTag("home-settings"),
                            onClick = onOpenSettings,
                        ) { Text("Settings") }
                    },
                )
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
                if (catalog.isRefreshing && sortedApps.isEmpty()) {
                    item(key = "catalog-loading") {
                        Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator()
                            Text("Loading apps…", modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                } else if (sortedApps.isEmpty() && catalog.lastError == null) {
                    item(key = "catalog-empty") {
                        Text("No apps available", modifier = Modifier.fillMaxWidth().padding(24.dp))
                    }
                }
                items(sortedApps, key = { it.key.stableId }) { app ->
                    val profileName = profiles[app.key.profileSerial] ?: "Other profile"
                    val quiet = catalog.profiles.firstOrNull { it.serialNumber == app.key.profileSerial }?.quietModeEnabled == true
                    HomeAppRow(
                        app = app,
                        profileName = profileName,
                        launcherApps = launcherApps,
                        userManager = userManager,
                        enabled = app.enabled && !quiet,
                        catalogGeneration = catalog.generation,
                        onClick = { onLaunch(app) },
                    )
                }
                item(key = "home-actions") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (catalog.isRefreshing && sortedApps.isNotEmpty()) {
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
}
