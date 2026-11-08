package io.github.alexanderwilhelmsenberg.modular.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexanderwilhelmsenberg.modular.BuildConfig
import io.github.alexanderwilhelmsenberg.modular.R
import io.github.alexanderwilhelmsenberg.modular.appearance.AppearanceTheme
import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.composition.ActiveContextState
import io.github.alexanderwilhelmsenberg.modular.composition.LauncherContext
import io.github.alexanderwilhelmsenberg.modular.composition.ModuleInstance
import io.github.alexanderwilhelmsenberg.modular.events.CoreEventBusDiagnostics
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleManagementEvent
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleRecord
import io.github.alexanderwilhelmsenberg.modular.moduleapi.CapabilityId
import io.github.alexanderwilhelmsenberg.modular.security.ModuleSecurityStatus

enum class SettingsPage { ROOT, PRODUCTIVITY, APPEARANCE, ADDONS, ADVANCED }

@Composable
fun ModularApp(
    homeRoleAvailable: Boolean,
    homeRoleHeld: Boolean,
    modules: List<ModuleRecord>,
    appCatalog: AppCatalog,
    instances: List<ModuleInstance>,
    contexts: List<LauncherContext>,
    activeContext: ActiveContextState,
    compositionLoadError: String?,
    contextActivationError: String?,
    eventBusDiagnostics: CoreEventBusDiagnostics,
    moduleSecurity: List<ModuleSecurityStatus>,
    grantRecordCount: Int,
    grantLoadError: String?,
    onRequestHomeRole: () -> Unit,
    onRefreshModules: () -> Unit,
    onRefreshApps: () -> Unit,
    onActivateContext: (String) -> Unit,
    onDeactivateContext: () -> Unit,
    onBack: () -> Unit = {},
    appearanceTheme: AppearanceTheme = AppearanceTheme.LIGHT,
    appearanceError: String? = null,
    onAppearanceThemeSelected: (AppearanceTheme) -> Unit = {},
    managementEvents: List<ModuleManagementEvent> = emptyList(),
    onSetModuleEnabled: (ModuleRecord, Boolean) -> Unit = { _, _ -> },
    onRetryModule: (ModuleRecord) -> Unit = {},
    onUninstallModule: (ModuleRecord) -> Unit = {},
    onSetGrant: (ModuleRecord, CapabilityId, Boolean) -> Unit = { _, _, _ -> },
    onOpenModuleSettings: (ModuleRecord) -> Unit = {},
    onClearModuleEvents: () -> Unit = {},
    compositionContent: @Composable () -> Unit = {},
    appearanceContent: @Composable () -> Unit = {},
    coreFeaturesContent: @Composable () -> Unit = {},
    corePackageName: String = "io.github.alexanderwilhelmsenberg.modular",
    initialPage: SettingsPage = SettingsPage.ROOT,
    pageRequest: Int = 0,
) {
    var currentPage by rememberSaveable(initialPage, pageRequest) { mutableStateOf(initialPage) }
    val context = LocalContext.current
    val goBack = { if (currentPage == SettingsPage.ROOT) onBack() else currentPage = SettingsPage.ROOT }
    BackHandler(onBack = goBack)

    BoxWithConstraints(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding(),
    ) {
        val compact = maxHeight < 700.dp || LocalDensity.current.fontScale >= 1.3f
        val rootTopGap = if (compact) (maxHeight * 0.045f).coerceIn(12.dp, 36.dp)
            else (maxHeight * 0.08f).coerceIn(24.dp, 80.dp)
        val subTopGap = if (compact) (maxHeight * 0.04f).coerceIn(12.dp, 28.dp)
            else (maxHeight * 0.07f).coerceIn(28.dp, 64.dp)
        key(currentPage) {
        val listState = rememberLazyListState()
        LazyColumn(
            modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 640.dp)
                .fillMaxWidth().padding(top = 64.dp).fillMaxSize().testTag("settings-list"),
            state = listState,
            contentPadding = PaddingValues(bottom = 40.dp),
        ) {
            item(key = "settings-header") {
                Column {
                    if (currentPage == SettingsPage.ROOT) {
                        Spacer(Modifier.height(rootTopGap))
                        Image(
                            painter = painterResource(R.drawable.ic_modular_mark),
                            contentDescription = null,
                            modifier = Modifier.size(if (compact) 64.dp else 88.dp).align(Alignment.CenterHorizontally),
                        )
                        Spacer(Modifier.height(if (compact) 12.dp else 18.dp))
                        Text(
                            "Modular settings",
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                                .padding(horizontal = 24.dp).testTag("settings-root-title"),
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.headlineMedium,
                            fontSize = 28.sp,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(if (compact) 20.dp else 44.dp))
                    } else {
                        Spacer(Modifier.height(subTopGap))
                        Text(
                            currentPage.title,
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 24.dp),
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.headlineMedium,
                            fontSize = 28.sp,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(if (compact) 20.dp else 36.dp))
                    }
                }
            }
            when (currentPage) {
                SettingsPage.ROOT -> {
                    item(key = "open-productivity") {
                        SettingsRow("Productivity", "Categories, folders and app order", SettingsIcon.PRODUCTIVITY,
                            Modifier.testTag("settings-open-productivity"), onClick = { currentPage = SettingsPage.PRODUCTIVITY })
                    }
                    item(key = "open-appearance") {
                        SettingsRow("Appearance", "Theme and Home display", SettingsIcon.APPEARANCE,
                            Modifier.testTag("settings-open-appearance"), onClick = { currentPage = SettingsPage.APPEARANCE })
                    }
                    item(key = "open-addons") {
                        SettingsRow("Add-ons", "Modules and activity", SettingsIcon.ADDONS,
                            Modifier.testTag("settings-open-addons"), onClick = { currentPage = SettingsPage.ADDONS })
                    }
                    item(key = "open-advanced") {
                        SettingsRow("Advanced", "Launcher and diagnostics", SettingsIcon.ADVANCED,
                            Modifier.testTag("settings-open-advanced"), onClick = { currentPage = SettingsPage.ADVANCED })
                    }
                }
                SettingsPage.PRODUCTIVITY -> {
                    item(key = "productivity-label") { SettingsSectionLabel("Home setup") }
                    item(key = "core-features") { Column(Modifier.fillMaxWidth()) { coreFeaturesContent() } }
                }
                SettingsPage.APPEARANCE -> {
                    item(key = "appearance-label") { SettingsSectionLabel("Theme") }
                    item(key = "appearance") {
                        Column(Modifier.fillMaxWidth()) {
                            AppearanceSelector(appearanceTheme, appearanceError, onAppearanceThemeSelected)
                        }
                    }
                    item(key = "home-appearance-label") { SettingsSectionLabel("Home display") }
                    item(key = "home-appearance") { Column(Modifier.fillMaxWidth()) { appearanceContent() } }
                }
                SettingsPage.ADDONS -> {
                    item(key = "addons-label") { SettingsSectionLabel("Modules") }
                    item(key = "refresh-modules") {
                        SettingsRow("Refresh module list", "Scan installed add-ons", SettingsIcon.ADDONS, onClick = onRefreshModules)
                    }
                    if (modules.isEmpty()) {
                        item(key = "no-modules") {
                            Text("No compatible module services discovered.",
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    items(modules, key = { it.component.flattenToString() }) { module ->
                        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp)) {
                            ModuleManagementCard(
                                module = module,
                                securityStatus = moduleSecurity.firstOrNull { it.packageName == module.component.packageName },
                                corePackageName = corePackageName,
                                onSetEnabled = onSetModuleEnabled,
                                onRetry = onRetryModule,
                                onUninstall = onUninstallModule,
                                onSetGrant = onSetGrant,
                                onOpenSettings = onOpenModuleSettings,
                            )
                        }
                    }
                    item(key = "event-label") { SettingsSectionLabel("Activity") }
                    item(key = "event-log") {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                            ModuleEventLog(events = managementEvents, modules = modules, onClear = onClearModuleEvents)
                        }
                    }
                }
                SettingsPage.ADVANCED -> {
                    item(key = "launcher-label") { SettingsSectionLabel("Launcher") }
                    item(key = "launcher-role") {
                        SettingsRow(
                            title = "Default launcher",
                            description = when {
                                !homeRoleAvailable -> "HOME role is unavailable on this device."
                                homeRoleHeld -> "Modular is your default launcher."
                                else -> "Choose Modular as your default launcher."
                            },
                            icon = SettingsIcon.HOME,
                            onClick = if (homeRoleAvailable && !homeRoleHeld) onRequestHomeRole else null,
                        )
                    }
                    item(key = "composition") { Column(Modifier.fillMaxWidth()) { compositionContent() } }
                    item(key = "home-status") {
                        SettingsRow("Built-in Home", "Works without add-ons", SettingsIcon.HOME)
                    }
                    item(key = "app-info") {
                        SettingsRow(
                            "App info", "Modular ${BuildConfig.VERSION_NAME}", SettingsIcon.INFO,
                            Modifier.testTag("settings-app-info"),
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null)))
                            },
                        )
                    }
                    item(key = "diagnostics-label") { SettingsSectionLabel("Diagnostics") }
                    item(key = "diagnostics") {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                            DiagnosticsSection(
                                modules = modules,
                                appCatalog = appCatalog,
                                instances = instances,
                                contexts = contexts,
                                activeContext = activeContext,
                                compositionLoadError = compositionLoadError,
                                contextActivationError = contextActivationError,
                                eventBusDiagnostics = eventBusDiagnostics,
                                moduleSecurity = moduleSecurity,
                                grantRecordCount = grantRecordCount,
                                grantLoadError = grantLoadError,
                                onRefreshModules = onRefreshModules,
                                onRefreshApps = onRefreshApps,
                                onActivateContext = onActivateContext,
                                onDeactivateContext = onDeactivateContext,
                            )
                        }
                    }
                }
            }
        }
        }
        Box(
            Modifier.align(Alignment.TopCenter).widthIn(max = 640.dp).fillMaxWidth()
                .height(64.dp).background(MaterialTheme.colorScheme.background),
        ) { SettingsBackButton(goBack) }
    }
}

private val SettingsPage.title: String
    get() = when (this) {
        SettingsPage.ROOT -> "Settings"
        SettingsPage.PRODUCTIVITY -> "Productivity"
        SettingsPage.APPEARANCE -> "Appearance"
        SettingsPage.ADDONS -> "Add-ons"
        SettingsPage.ADVANCED -> "Advanced"
    }

@Composable
private fun SettingsBackButton(onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onBackground
    IconButton(
        onClick = onClick,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp).size(48.dp)
            .semantics { contentDescription = "Back" }.testTag("settings-back"),
    ) {
        Canvas(Modifier.size(24.dp)) {
            val unit = size.width / 24f
            drawLine(ink, Offset(20f * unit, 12f * unit),
                Offset(4f * unit, 12f * unit), 1.8f * unit, cap = StrokeCap.Round)
            drawLine(ink, Offset(4f * unit, 12f * unit),
                Offset(11f * unit, 5f * unit), 1.8f * unit, cap = StrokeCap.Round)
            drawLine(ink, Offset(4f * unit, 12f * unit),
                Offset(11f * unit, 19f * unit), 1.8f * unit, cap = StrokeCap.Round)
        }
    }
}

@Composable
internal fun AppearanceSelector(
    selected: AppearanceTheme,
    error: String?,
    onSelect: (AppearanceTheme) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            AppearanceTheme.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = selected == option,
                            onClick = { onSelect(option) },
                            role = Role.RadioButton,
                        )
                        .testTag("appearance-${option.id}")
                        .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected == option, onClick = null)
                    Spacer(Modifier.width(28.dp))
                    Text(option.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        error?.let { Text(it, modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
internal fun SecurityStatusCard(
    statuses: List<ModuleSecurityStatus>,
    grantRecordCount: Int,
    loadError: String?,
) {
    val body = buildString {
        append(grantRecordCount)
        append(" persisted module identities")

        val requested = statuses.sumOf {
            it.requestedCapabilities.size
        }
        val effective = statuses.sumOf {
            it.effectiveGrants.size
        }

        append("\n")
        append(requested)
        append(" requested Core capabilities · ")
        append(effective)
        append(" effective grants")

        val unsupported = statuses.sumOf {
            it.unsupportedRequests.size
        }
        if (unsupported > 0) {
            append("\n")
            append(unsupported)
            append(" unsupported capability requests")
        }

        loadError?.let { error ->
            append("\nStorage error: ")
            append(error)
        }
    }

    StatusCard(
        title = "Module security",
        body = body,
    )
}

@Composable
internal fun EventBusStatusCard(
    diagnostics: CoreEventBusDiagnostics,
) {
    val body = buildString {
        append("Published sequence: ")
        append(diagnostics.latestPublishedSequence)
        append("\nDelivered sequence: ")
        append(diagnostics.latestDeliveredSequence)
        append("\nDropped before delivery: ")
        append(diagnostics.droppedBeforeDelivery)

        if (diagnostics.droppedBeforeDelivery > 0) {
            append(
                "\nSequence gaps require a state resync.",
            )
        }
    }

    StatusCard(
        title = "Core event bus",
        body = body,
    )
}

@Composable
internal fun CompositionStatusCard(
    instances: List<ModuleInstance>,
    contexts: List<LauncherContext>,
    activeContext: ActiveContextState,
    loadError: String?,
    activationError: String?,
    onDeactivateContext: () -> Unit,
) {
    val body = buildString {
        append(instances.size)
        append(" instances · ")
        append(contexts.size)
        append(" contexts")

        val active = activeContext.context
        if (active == null) {
            append("\nNo active context")
        } else {
            append("\nActive: ")
            append(active.name)
            append(
                if (activeContext.isReady) {
                    " · ready"
                } else {
                    " · degraded"
                },
            )

            activeContext.problems.forEach { problem ->
                append("\n")
                append(problem.capability.wireValue)
                append(": ")
                append(problem.detail)
            }
        }

        loadError?.let {
            append("\nStorage error: ")
            append(it)
        }

        activationError?.let {
            append("\nActivation error: ")
            append(it)
        }
    }

    StatusCard(
        title = "Composition",
        body = body,
        actionText =
            if (activeContext.context != null) {
                "Deactivate"
            } else {
                null
            },
        onAction = onDeactivateContext,
    )
}

@Composable
internal fun ContextCard(
    context: LauncherContext,
    activeContext: ActiveContextState,
    onActivate: (String) -> Unit,
) {
    val isActive = activeContext.context?.id == context.id

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement =
                Arrangement.spacedBy(6.dp),
        ) {
            Text(
                context.name,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                context.bindings.size.toString() +
                    " capability bindings",
                style = MaterialTheme.typography.bodySmall,
            )

            context.bindings.forEach { binding ->
                Text(
                    binding.capability.wireValue +
                        " → " + binding.instanceId,
                    style =
                        MaterialTheme.typography.bodySmall,
                )
            }

            if (isActive) {
                Text(
                    if (activeContext.isReady) {
                        "Active · ready"
                    } else {
                        "Active · degraded"
                    },
                    style =
                        MaterialTheme.typography.bodyMedium,
                )
            } else {
                Button(
                    onClick = {
                        onActivate(context.id)
                    },
                ) {
                    Text("Activate")
                }
            }
        }
    }
}

@Composable
internal fun InstanceCard(
    instance: ModuleInstance,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement =
                Arrangement.spacedBy(6.dp),
        ) {
            Text(
                instance.name,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "Provider: " + instance.providerId,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "ID: " + instance.id,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun StatusCard(
    title: String,
    body: String,
    actionText: String? = null,
    onAction: () -> Unit = {},
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement =
                Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
            )

            if (actionText != null) {
                Button(onClick = onAction) {
                    Text(actionText)
                }
            }
        }
    }
}
