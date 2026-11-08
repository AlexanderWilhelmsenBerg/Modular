package io.github.alexanderwilhelmsenberg.modular

import android.os.Bundle
import android.content.ComponentName
import android.content.pm.ServiceInfo
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.rememberLazyListState
import io.github.alexanderwilhelmsenberg.modular.ui.HomeScreen
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import io.github.alexanderwilhelmsenberg.modular.appearance.AppearanceHomeControls
import io.github.alexanderwilhelmsenberg.modular.appearance.HomeBackground
import io.github.alexanderwilhelmsenberg.modular.appearance.applyHomeWindowAppearance
import io.github.alexanderwilhelmsenberg.modular.capabilities.FeaturedAppsClient
import io.github.alexanderwilhelmsenberg.modular.home.HomeFeaturedController
import io.github.alexanderwilhelmsenberg.modular.home.HomeFeaturedState
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedAppsResultParcel
import io.github.alexanderwilhelmsenberg.modular.ui.FeaturedAppSettingsUi
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationViewModel
import io.github.alexanderwilhelmsenberg.modular.ui.OrganizationHomeScreen
import io.github.alexanderwilhelmsenberg.modular.ui.OrganizationSettingsHost
import io.github.alexanderwilhelmsenberg.modular.appearance.AppearanceSettingsStore
import io.github.alexanderwilhelmsenberg.modular.appearance.ModularTheme
import io.github.alexanderwilhelmsenberg.modular.capabilities.AppSortClient
import io.github.alexanderwilhelmsenberg.modular.home.HomeSortController
import io.github.alexanderwilhelmsenberg.modular.apps.AndroidAppRepository
import io.github.alexanderwilhelmsenberg.modular.apps.AppRepository
import io.github.alexanderwilhelmsenberg.modular.apps.AndroidAppActions
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.apps.AppCustomizationStore
import io.github.alexanderwilhelmsenberg.modular.ui.HiddenAppsControls
import io.github.alexanderwilhelmsenberg.modular.ui.RenameAppDialog
import io.github.alexanderwilhelmsenberg.modular.ui.CoreMostUsedSettingsUi
import io.github.alexanderwilhelmsenberg.modular.mostused.MostUsedSettingsStore
import io.github.alexanderwilhelmsenberg.modular.mostused.LocalMostUsedHistoryStore
import io.github.alexanderwilhelmsenberg.modular.mostused.MostUsedSource
import io.github.alexanderwilhelmsenberg.modular.mostused.BuiltInMostUsedController
import io.github.alexanderwilhelmsenberg.modular.mostused.AndroidMostUsedUsageSource
import io.github.alexanderwilhelmsenberg.modular.mostused.LegacyMostUsedMigration
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityRouter
import io.github.alexanderwilhelmsenberg.modular.capabilities.CoreHostFactory
import io.github.alexanderwilhelmsenberg.modular.composition.ContextManager
import io.github.alexanderwilhelmsenberg.modular.composition.DataStoreCompositionStateStore
import io.github.alexanderwilhelmsenberg.modular.composition.ModuleSettingsController
import io.github.alexanderwilhelmsenberg.modular.composition.InstanceStore
import io.github.alexanderwilhelmsenberg.modular.events.CoreEventBus
import io.github.alexanderwilhelmsenberg.modular.events.CoreEventSources
import io.github.alexanderwilhelmsenberg.modular.home.HomeRoleController
import io.github.alexanderwilhelmsenberg.modular.moduleapi.CapabilityId
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleRegistry
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleRecord
import io.github.alexanderwilhelmsenberg.modular.security.ModuleAuthorizationContext
import io.github.alexanderwilhelmsenberg.modular.ui.CompositionManagement
import io.github.alexanderwilhelmsenberg.modular.security.AndroidModuleIdentityResolver
import io.github.alexanderwilhelmsenberg.modular.security.DataStoreModuleGrantStateStore
import io.github.alexanderwilhelmsenberg.modular.security.ModuleSecurityManager
import io.github.alexanderwilhelmsenberg.modular.security.OfficialModuleBootstrapPolicy
import io.github.alexanderwilhelmsenberg.modular.moduleapi.ModuleContract
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleDescriptorDiscovery
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleConnectionStatus
import io.github.alexanderwilhelmsenberg.modular.ui.ModuleSettingsDialog
import io.github.alexanderwilhelmsenberg.modular.ui.ModularApp
import io.github.alexanderwilhelmsenberg.modular.ui.SettingsPage
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class MainActivity : ComponentActivity() {
    private lateinit var homeRoleController: HomeRoleController
    private lateinit var moduleRegistry: ModuleRegistry
    private lateinit var capabilityRouter: CapabilityRouter
    private lateinit var appRepository: AppRepository
    private lateinit var appActions: AndroidAppActions
    private lateinit var appCustomizationStore: AppCustomizationStore
    private lateinit var mostUsedStore: MostUsedSettingsStore
    private lateinit var organization: OrganizationViewModel
    private lateinit var builtInMostUsed: BuiltInMostUsedController
    private lateinit var mostUsedMigration: LegacyMostUsedMigration
    private lateinit var compositionStore:
        DataStoreCompositionStateStore
    private lateinit var instanceStore: InstanceStore
    private lateinit var contextManager: ContextManager
    private lateinit var moduleSettingsController: ModuleSettingsController
    private lateinit var eventBus: CoreEventBus
    private lateinit var eventSources: CoreEventSources
    private lateinit var grantStore:
        DataStoreModuleGrantStateStore
    private lateinit var securityManager:
        ModuleSecurityManager

    private lateinit var appearanceStore: AppearanceSettingsStore
    private lateinit var appSortClient: AppSortClient
    private lateinit var homeSortController: HomeSortController
    private lateinit var featuredAppsClient: FeaturedAppsClient
    private lateinit var homeFeaturedController: HomeFeaturedController
    private var homeSearchQuery by mutableStateOf("")
    private var homeIntentGeneration by mutableStateOf(0)

    private var settingsVisible by mutableStateOf(false)
    private var selectedModuleComponent by mutableStateOf<String?>(null)
    private var pendingUninstallPackage: String? = null

    private val appUninstallLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { appRepository.refresh() }

    private val uninstallLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        moduleRegistry.refresh()
        appRepository.refresh()
        pendingUninstallPackage?.let { packageName ->
            val installed = try {
                packageManager.getApplicationInfo(packageName, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
            moduleRegistry.managementStore.record(
                packageName, "uninstall",
                if (installed) "Package remains installed; uninstall was cancelled or not completed."
                else "Package uninstalled. Saved instances and contexts were retained.",
            )
        }
        pendingUninstallPackage = null
    }

    private var homeRoleAvailable by mutableStateOf(false)
    private var homeRoleHeld by mutableStateOf(false)

    private val homeRoleLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) {
            refreshHomeRoleState()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        homeSearchQuery = savedInstanceState?.getString("homeSearchQuery").orEmpty()
        homeIntentGeneration = savedInstanceState?.getInt("homeIntentGeneration") ?: 0
        appearanceStore = AppearanceSettingsStore(this)
        appCustomizationStore = AppCustomizationStore(this)
        mostUsedStore = MostUsedSettingsStore(this)
        organization = ViewModelProvider(this)[OrganizationViewModel::class.java]
        settingsVisible = savedInstanceState?.getBoolean("settingsVisible") ?: false
        pendingUninstallPackage = savedInstanceState?.getString("pendingUninstallPackage")
        selectedModuleComponent = savedInstanceState?.getString("selectedModuleComponent")
        if (savedInstanceState == null) handleModuleSettingsIntent(intent)

        homeRoleController = HomeRoleController(this)
        appRepository = AndroidAppRepository(this)
        appActions = AndroidAppActions(this)
        builtInMostUsed = BuiltInMostUsedController(
            appRepository, mostUsedStore, AndroidMostUsedUsageSource(this),
            CoroutineScope(lifecycleScope.coroutineContext + Dispatchers.Default),
            displayCustomizations = appCustomizationStore.customizations,
            localHistory = LocalMostUsedHistoryStore(this),
            organization = organization.store.snapshots,
        )
        grantStore =
            DataStoreModuleGrantStateStore(this)
        securityManager = ModuleSecurityManager(
            identityResolver =
                AndroidModuleIdentityResolver(this),
            grantStore = grantStore,
            officialBootstrapCapabilities =
                OfficialModuleBootstrapPolicy
                    .capabilities,
        )

        val hostFactory = CoreHostFactory(
            context = this,
            appRepository = appRepository,
            securityManager = securityManager,
        )

        moduleRegistry = ModuleRegistry(
            context = this,
            hostFactory = hostFactory,
        )
        moduleRegistry.setConnectionDemand(emptySet())
        capabilityRouter = CapabilityRouter(moduleRegistry)

        compositionStore =
            DataStoreCompositionStateStore(this)
        instanceStore = InstanceStore(compositionStore, capabilityRouter.routes)
        moduleSettingsController = ModuleSettingsController(compositionStore, capabilityRouter.routes)
        mostUsedMigration = LegacyMostUsedMigration(moduleSettingsController, mostUsedStore, packageName)
        contextManager = ContextManager(
            stateStore = compositionStore,
            routes = capabilityRouter.routes,
        )

        appSortClient = AppSortClient(capabilityRouter)
        homeSortController = HomeSortController(
            catalog = appRepository.catalog,
            activeContext = contextManager.active,
            routes = capabilityRouter.routes,
            sort = appSortClient::sort,
            localeTag = resources.configuration.locales[0].toLanguageTag(),
            onFailure = { route, message ->
                moduleRegistry.managementStore.record(
                    route.component?.flattenToString() ?: route.providerId,
                    "sort", message, true,
                )
            },
        )

        featuredAppsClient = FeaturedAppsClient(capabilityRouter)
        homeFeaturedController = HomeFeaturedController(
            catalog = appRepository.catalog,
            activeContext = contextManager.active,
            routes = capabilityRouter.routes,
            client = featuredAppsClient,
            localeTag = resources.configuration.locales[0].toLanguageTag(),
            onFailure = { route, message ->
                moduleRegistry.managementStore.record(
                    route.component?.flattenToString() ?: route.providerId,
                    "featured", message, true,
                )
            },
        )

        eventBus = CoreEventBus()
        eventSources = CoreEventSources(
            eventBus = eventBus,
            modules = moduleRegistry.modules,
            appCatalog = appRepository.catalog,
            composition = compositionStore.state,
            grants = grantStore.state,
            activeContext = contextManager.active,
        )

        refreshHomeRoleState()
        moduleRegistry.refresh()

        lifecycleScope.launch {
            // Read local appearance before the first Compose frame, without blocking Main.
            val initialTheme = appearanceStore.currentTheme()
            val initialHomeAppearance = appearanceStore.currentSettings()
            val initialCustomizations = appCustomizationStore.currentCustomizations()
            val initialOrganization = organization.store.currentSnapshot()
            organization.runtime.initialize()
            val initialMostUsed = mostUsedStore.currentSettings()
            val initialWallpaper = applyHomeWindowAppearance(
                this@MainActivity, initialTheme,
                initialHomeAppearance.background == HomeBackground.SYSTEM_WALLPAPER,
                !settingsVisible,
            )
            setContent {
                val appearanceTheme by appearanceStore.theme.collectAsState(initial = initialTheme)
                val appearanceError by appearanceStore.error.collectAsState()
                val homeAppearance by appearanceStore.settings.collectAsState(initial = initialHomeAppearance)
                val appCustomizations by appCustomizationStore.customizations.collectAsState(initial = initialCustomizations)
                val customizationError by appCustomizationStore.error.collectAsState()
                val mostUsedSettings by mostUsedStore.settings.collectAsState(initial = initialMostUsed)
                val organizationSnapshot by organization.store.snapshots.collectAsState(initial = initialOrganization)
                LaunchedEffect(organizationSnapshot.folders) {
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                        organization.runtime.signals.start(organizationSnapshot.folders)
                }
                val mostUsedState by builtInMostUsed.state.collectAsState()
                val mostUsedError by mostUsedStore.error.collectAsState()
                var mostUsedConfigureRequest by rememberSaveable { mutableStateOf(0) }
                var migrationError by remember { mutableStateOf<String?>(null) }
                var migrationRetry by remember { mutableStateOf(0) }
                var renameTarget by remember { mutableStateOf<LaunchableApp?>(null) }
                LaunchedEffect(homeIntentGeneration, settingsVisible) {
                    renameTarget = null
                    if (!settingsVisible) mostUsedConfigureRequest = 0
                }
                val featured by homeFeaturedController.featured.collectAsState()
                val externalFeatured = featured?.takeUnless {
                    mostUsedSettings.enabled ||
                        (mostUsedSettings.userConfigured && LegacyMostUsedMigration.isLegacy(it, packageName))
                }
                LaunchedEffect(featured?.binding, featured?.featureRevision, migrationRetry) {
                    featured?.takeIf { LegacyMostUsedMigration.isLegacy(it, packageName) }?.let { legacy ->
                        mostUsedMigration.migrate(legacy).onSuccess { migrationError = null }
                            .onFailure { migrationError = "Could not move Most Used settings to Core: ${it.message ?: "try again"}" }
                    }
                }
                var wallpaperActive by remember { mutableStateOf(initialWallpaper) }
                val homeOrderedApps by homeSortController.orderedApps.collectAsState()
                val customHomeOrderActive by homeSortController.customOrderActive.collectAsState()
                LaunchedEffect(appearanceTheme, homeAppearance.background, settingsVisible) {
                    wallpaperActive = applyHomeWindowAppearance(
                        this@MainActivity, appearanceTheme,
                        homeAppearance.background == HomeBackground.SYSTEM_WALLPAPER,
                        !settingsVisible,
                    )
                }
                ModularTheme(appearanceTheme) {
                    val modules by moduleRegistry.modules.collectAsState()
                    val appCatalog by appRepository.catalog.collectAsState()
                    val visibleCatalog = remember(appCatalog, appCustomizations) {
                        appCatalog.copy(apps = appCustomizations.visibleApps(appCatalog.apps))
                    }
                    val visibleOrderedApps = remember(homeOrderedApps, appCustomizations) {
                        homeOrderedApps?.let(appCustomizations::visibleApps)
                    }
                    val instances by instanceStore.instances.collectAsState()
                    val compositionState by compositionStore.state.collectAsState()
                    val availableRoutes by capabilityRouter.routes.collectAsState()
                    val contexts by contextManager.contexts.collectAsState()
                    val activeContext by contextManager.active.collectAsState()
                    val activationError by
                        contextManager.activationError.collectAsState()
                    val compositionLoadError by
                        compositionStore.loadError.collectAsState()
                    val eventDiagnostics by
                        eventBus.diagnostics.collectAsState()
                    val grantState by
                        grantStore.state.collectAsState()
                    val grantLoadError by
                        grantStore.loadError.collectAsState()

                    val managementEvents by moduleRegistry.managementStore.events.collectAsState()
                    // Settings requests inspection sessions. Home needs only providers selected
                    // by its persisted context; the built-in launcher requires no module.
                    LaunchedEffect(settingsVisible, modules, activeContext.context, instances) {
                        val providerIds = activeContext.context?.bindings.orEmpty().mapNotNull { binding ->
                            instances.firstOrNull { it.id == binding.instanceId }?.providerId
                        }.toSet()
                        moduleRegistry.setConnectionDemand(
                            if (settingsVisible) null else modules.filter { module ->
                                (module.moduleId ?: moduleRegistry.managementStore.knownProviderId(module.component)) in providerIds
                            }.map { it.component }.toSet(),
                        )
                    }

                    LaunchedEffect(availableRoutes, activeContext.context) {
                        moduleSettingsController.migrateActiveLegacySortToFeatured().onFailure {
                            moduleRegistry.managementStore.record(
                                "Core", "featured-migration",
                                "Could not upgrade the selected app order. Retry in module Settings.", true,
                            )
                        }
                    }
                    val homeStateHolder = rememberSaveableStateHolder()
                    BackHandler { settingsVisible = false }

                    if (!settingsVisible) {
                        homeStateHolder.SaveableStateProvider("home") {
                        key(homeIntentGeneration) {
                            val homeListState = rememberLazyListState()
                            OrganizationHomeScreen(
                                catalog = visibleCatalog,
                                runtime = organization.runtime,
                                snapshot = organizationSnapshot,
                                mostUsedEnabled = mostUsedSettings.enabled,
                                hiddenKeys = appCatalog.apps.filter { appCustomizations.isHidden(it.key) }.mapTo(HashSet()) { it.key },
                                sorterController = organization.sorter,
                                onExitSorter = organization::exitSorter,
                                onStartSorter = { left, right ->
                                    homeSearchQuery = ""
                                    organization.startSorter(organizationSnapshot, visibleCatalog.apps, left, right)
                                },
                                homeResetToken = homeIntentGeneration,
                                orderedApps = visibleOrderedApps.takeIf { customHomeOrderActive && !mostUsedSettings.enabled },
                                showAppIcons = homeAppearance.showAppIcons,
                                wallpaperMode = wallpaperActive,
                                wallpaperDimmingPercent = homeAppearance.wallpaperDimming,
                                featuredApps = if (mostUsedSettings.enabled) mostUsedState.featuredApps else externalFeatured?.featuredApps,
                                pinnedAppKeys = if (mostUsedSettings.enabled) mostUsedSettings.pinnedKeys.toSet() else externalFeatured?.pinnedKeys?.toSet().orEmpty(),
                                featuredNeedsPermission = if (mostUsedSettings.enabled)
                                    mostUsedSettings.source == MostUsedSource.ANDROID && mostUsedSettings.suggestedCount > 0 && !mostUsedState.hasUsageAccess
                                    else externalFeatured?.featureState == FeaturedAppsResultParcel.STATE_PERMISSION_REQUIRED,
                                featuredMessage = if (mostUsedSettings.enabled) mostUsedState.message else externalFeatured?.message?.takeIf(String::isNotBlank) ?: when (externalFeatured?.featureState) {
                                    FeaturedAppsResultParcel.STATE_PERMISSION_REQUIRED ->
                                        "Usage Access is off. Pins still appear; open add-on settings to grant access."
                                    FeaturedAppsResultParcel.STATE_NO_HISTORY ->
                                        "No usage history yet. Pin apps from add-on settings."
                                    else -> null
                                },
                                alphabeticalSections = mostUsedSettings.enabled || !customHomeOrderActive,
                                onOpenFeaturedSettings = if (mostUsedSettings.enabled) ({
                                    mostUsedConfigureRequest += 1
                                    settingsVisible = true
                                }) else externalFeatured?.let { current -> {
                                    if (homeFeaturedController.featured.value?.binding == current.binding) {
                                        selectedModuleComponent = current.binding.route.component?.flattenToString()
                                        settingsVisible = true
                                    } else {
                                        Toast.makeText(this@MainActivity, "The selected add-on changed. Try again.", Toast.LENGTH_LONG).show()
                                    }
                                } },
                                onOpenFeaturedManagement = if (mostUsedSettings.enabled) ::openCoreUsageAccess else externalFeatured?.let { current -> {
                                    openFeaturedManagement(current, modules)
                                } },
                                onPinApp = if (mostUsedSettings.enabled) ({ app, pinned ->
                                    lifecycleScope.launch {
                                        builtInMostUsed.setPinned(app.key, pinned).onFailure {
                                            Toast.makeText(this@MainActivity, it.message ?: "Could not update pin", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }) else externalFeatured?.let { current -> { app, pinned ->
                                    lifecycleScope.launch {
                                        val result = if (homeFeaturedController.featured.value?.binding != current.binding) {
                                            Result.failure(IllegalStateException("The selected add-on changed. Try again."))
                                        } else homeFeaturedController.setPinned(app.key, pinned, current.binding)
                                        recordManagementResult(
                                            current.binding.route.component?.flattenToString() ?: "Core",
                                            "pins", result, "Updated pinned apps",
                                        )
                                    }
                                } },
                                searchQuery = homeSearchQuery,
                                onSearchQueryChange = { homeSearchQuery = it },
                                listState = homeListState,
                                showMakeDefault = homeRoleAvailable && !homeRoleHeld,
                                onLaunch = { app ->
                                    lifecycleScope.launch {
                                        builtInMostUsed.launchFromHome(app.key).onFailure {
                                            Toast.makeText(this@MainActivity, "Unable to open ${app.label}", Toast.LENGTH_LONG).show()
                                            appRepository.refresh()
                                        }
                                    }
                                },
                                onRefresh = appRepository::refresh,
                                onAppInfo = { app -> handleAppAction { appActions.showInfo(app.key) } },
                                onRenameApp = { app -> renameTarget = app },
                                onHideApp = { app -> lifecycleScope.launch {
                                    appCustomizationStore.setHidden(app.key, true).onFailure {
                                        Toast.makeText(this@MainActivity, "Could not hide ${app.label}", Toast.LENGTH_LONG).show()
                                    }.onSuccess {
                                        Toast.makeText(this@MainActivity, "App hidden. Restore it in Settings → Appearance → Hidden apps.", Toast.LENGTH_LONG).show()
                                    }
                                } },
                                onUninstallApp = { app -> handleAppAction {
                                    if (!appActions.uninstall(app.key, appUninstallLauncher::launch)) {
                                        Toast.makeText(this@MainActivity, "Use App info to uninstall from this profile.", Toast.LENGTH_LONG).show()
                                    }
                                } },
                                onMakeDefault = ::requestHomeRole,
                                onOpenSettings = { settingsVisible = true },
                            )
                        }
                        }
                    } else {
                        val securityStatuses = modules.groupBy { it.component.packageName }.map { (packageName, packageModules) ->
                            securityManager.status(
                                packageName = packageName,
                                requestedCapabilities = packageModules.flatMap { it.requestedCoreCapabilities }
                                    .mapNotNull(CapabilityId::parse).toSet(),
                            )
                        }

                        // Capture the entry destination once. Consuming a configure request or
                        // closing an add-on dialog must not reset the page being viewed.
                        val settingsEntryPage = rememberSaveable {
                            when {
                                mostUsedConfigureRequest > 0 -> SettingsPage.PRODUCTIVITY
                                selectedModuleComponent != null -> SettingsPage.ADDONS
                                else -> SettingsPage.ROOT
                            }
                        }
                        ModularApp(
                            initialPage = settingsEntryPage,
                            coreFeaturesContent = {
                                CoreMostUsedSettingsUi(
                                    enabled = mostUsedSettings.enabled,
                                    days = mostUsedSettings.days,
                                    suggestedCount = mostUsedSettings.suggestedCount,
                                    pinnedKeys = mostUsedSettings.pinnedKeys,
                                    unavailablePinnedKeys = mostUsedState.unavailablePinnedKeys,
                                    hasUsageAccess = mostUsedState.hasUsageAccess,
                                    catalog = visibleCatalog.apps,
                                    onSetEnabled = mostUsedStore::setEnabled,
                                    onSetDays = mostUsedStore::setDays,
                                    onSetSuggestedCount = mostUsedStore::setSuggestedCount,
                                    onSetPinned = builtInMostUsed::setPinned,
                                    onReorderPins = builtInMostUsed::reorderPins,
                                    onOpenUsageAccess = ::openCoreUsageAccess,
                                    onOpenAppInfo = { handleAppAction {
                                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                                    } },
                                    onRefresh = builtInMostUsed::refresh,
                                    loading = mostUsedState.loading,
                                    loadError = mostUsedError ?: mostUsedState.historyError,
                                    statusMessage = mostUsedState.message,
                                    configureRequest = mostUsedConfigureRequest,
                                    onConfigureRequestConsumed = { mostUsedConfigureRequest = 0 },
                                    hasImportedSettings = mostUsedSettings.importedLegacySources.isNotEmpty() && !mostUsedSettings.userConfigured,
                                    source = mostUsedSettings.source,
                                    onSetSource = mostUsedStore::setSource,
                                    onResetLearnedUsage = builtInMostUsed::resetLearnedUsage,
                                    usingLocalFallback = mostUsedState.usingLocalFallback,
                                )
                                OrganizationSettingsHost(
                                    snapshot = organizationSnapshot,
                                    catalog = visibleCatalog.apps,
                                    runtime = organization.runtime,
                                    mostUsedEnabled = mostUsedSettings.enabled,
                                    onStartSorter = { left, right ->
                                        homeSearchQuery = ""
                                        organization.startSorter(organizationSnapshot, visibleCatalog.apps, left, right)
                                        settingsVisible = false
                                    },
                                )
                                migrationError?.let { message ->
                                    Text(message, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                                    TextButton(onClick = { migrationRetry += 1; homeFeaturedController.refresh() }) { Text("Retry moving Most Used") }
                                }
                            },
                            appearanceTheme = appearanceTheme,
                            appearanceError = appearanceError,
                            onAppearanceThemeSelected = { selected ->
                                lifecycleScope.launch { appearanceStore.setTheme(selected) }
                            },
                            appearanceContent = {
                                AppearanceHomeControls(
                                    settings = homeAppearance,
                                    onBackgroundSelected = { value -> lifecycleScope.launch { appearanceStore.setBackground(value) } },
                                    onWallpaperDimmingSelected = { value -> lifecycleScope.launch { appearanceStore.setWallpaperDimming(value) } },
                                    onShowAppIconsChanged = { value -> lifecycleScope.launch { appearanceStore.setShowAppIcons(value) } },
                                )
                                HiddenAppsControls(
                                    apps = appCatalog.apps,
                                    customizations = appCustomizations,
                                    onUnhide = { appKey -> lifecycleScope.launch {
                                        appCustomizationStore.setHidden(appKey, false).onFailure {
                                            Toast.makeText(this@MainActivity, "Could not restore the app", Toast.LENGTH_LONG).show()
                                        }
                                    } },
                                )
                                customizationError?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
                            },
                            onBack = { settingsVisible = false },
                            corePackageName = packageName,
                            managementEvents = managementEvents,
                            onSetModuleEnabled = { module, enabled -> moduleRegistry.setEnabled(module.component, enabled) },
                            onRetryModule = { module -> moduleRegistry.retry(module.component) },
                            onUninstallModule = ::uninstallModule,
                            onOpenModuleSettings = { selectedModuleComponent = it.component.flattenToString() },
                            onClearModuleEvents = moduleRegistry.managementStore::clearEvents,
                            onSetGrant = { module, capability, granted ->
                                val status = securityStatuses.firstOrNull { it.packageName == module.component.packageName }
                                lifecycleScope.launch {
                                    val result = if (granted) {
                                        val identity = status?.identity
                                        if (identity == null) Result.failure(IllegalStateException("Module identity unavailable"))
                                        else securityManager.grant(ModuleAuthorizationContext(identity, status.requestedCapabilities), capability)
                                    } else securityManager.revoke(module.component.packageName, capability)
                                    recordManagementResult(module.component.flattenToString(), "permission", result,
                                        "${if (granted) "Granted" else "Revoked"} ${capability.wireValue}")
                                }
                            },
                            compositionContent = {
                                var advanced by remember { mutableStateOf(false) }
                                Column {
                                    TextButton(onClick = { advanced = !advanced }) {
                                        Text(if (advanced) "Hide advanced configuration" else "Advanced configuration")
                                    }
                                    if (advanced) CompositionManagement(
                                    instances = instances,
                                    contexts = contexts,
                                    modules = modules,
                                    activeContext = activeContext,
                                    routes = availableRoutes,
                                    onSaveInstance = { instance ->
                                        instanceStore.upsert(instance).map { Unit }.also {
                                            recordManagementResult("Core", "instance", it, "Saved instance ${instance.name}")
                                        }
                                    },
                                    onDeleteInstance = { id ->
                                        instanceStore.remove(id).also {
                                            recordManagementResult("Core", "instance", it, "Removed instance $id")
                                        }
                                    },
                                    onSaveContext = { context ->
                                        contextManager.upsert(context).map { Unit }.also {
                                            recordManagementResult("Core", "context", it, "Saved context ${context.name}")
                                        }
                                    },
                                    onDeleteContext = { id ->
                                        contextManager.remove(id).also {
                                            recordManagementResult("Core", "context", it, "Removed context $id")
                                        }
                                    },
                                    onActivateContext = ::activateContext,
                                    onDeactivateContext = ::deactivateContext,
                                    )
                                }
                            },
                            homeRoleAvailable = homeRoleAvailable,
                            homeRoleHeld = homeRoleHeld,
                            modules = modules,
                            appCatalog = appCatalog,
                            instances = instances,
                            contexts = contexts,
                            activeContext = activeContext,
                            compositionLoadError = compositionLoadError,
                            contextActivationError = activationError,
                            eventBusDiagnostics = eventDiagnostics,
                            moduleSecurity = securityStatuses,
                            grantRecordCount =
                                grantState.records.size,
                            grantLoadError = grantLoadError,
                            onRequestHomeRole = ::requestHomeRole,
                            onRefreshModules = moduleRegistry::refresh,
                            onRefreshApps = appRepository::refresh,
                            onActivateContext = ::activateContext,
                            onDeactivateContext = ::deactivateContext,
                        )
                    }
                    renameTarget?.let { target ->
                        val original = appCatalog.apps.firstOrNull { it.key == target.key }
                        if (original != null) RenameAppDialog(
                            app = original,
                            currentLabel = appCustomizations.labelFor(original),
                            originalLabel = original.label,
                            onDismiss = { renameTarget = null },
                            onSave = { label -> lifecycleScope.launch {
                                appCustomizationStore.setLabel(original.key, label)
                                    .onSuccess { renameTarget = null }
                                    .onFailure {
                                        Toast.makeText(this@MainActivity, "Could not save the app name", Toast.LENGTH_LONG).show()
                                    }
                            } },
                        )
                    }
                    if (settingsVisible && selectedModuleComponent != null) {
                        val selected = modules.firstOrNull {
                            it.component.flattenToString() == selectedModuleComponent
                        }
                        if (selected == null) {
                            AlertDialog(
                                onDismissRequest = { selectedModuleComponent = null },
                                title = { Text("Module settings") },
                                text = { Text("This add-on is not available yet. Refresh modules after installation, or check that it is still installed.") },
                                confirmButton = {
                                    TextButton(onClick = moduleRegistry::refresh) { Text("Refresh") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { selectedModuleComponent = null }) { Text("Close") }
                                },
                            )
                        } else {
                            val providerId = selected.moduleId ?: selected.descriptor?.moduleId
                            val configuration = remember(providerId, compositionState) {
                                providerId?.let(moduleSettingsController::configurationFor) ?: "{}"
                            }
                            val usedForAppOrder = remember(providerId, compositionState) {
                                providerId?.let(moduleSettingsController::isUsedForAppOrder) ?: false
                            }
                            val selectedFeaturedBinding = activeContext.resolvedBindings.firstOrNull {
                                it.capability == StandardCapabilities.AppsFeatured &&
                                    it.route.component == selected.component &&
                                    it.instance.providerId == providerId
                            }
                            val selectedFeatured = featured?.takeIf { it.binding == selectedFeaturedBinding }
                            ModuleSettingsDialog(
                                module = selected,
                                configurationJson = configuration,
                                isUsedForAppOrder = usedForAppOrder,
                                available = selected.status == ModuleConnectionStatus.CONNECTED &&
                                    availableRoutes.any { it.component == selected.component },
                                onSave = { json, useForAppOrder ->
                                    val result = if (providerId == null) {
                                        Result.failure(IllegalStateException("Module identity is unavailable. Retry its connection."))
                                    } else moduleSettingsController.save(providerId, selected.label, json, useForAppOrder)
                                    recordManagementResult(selected.component.flattenToString(), "settings", result,
                                        "Saved settings for ${selected.label}")
                                    if (result.isSuccess) appRepository.refresh()
                                    result
                                },
                                onOpenAddonSettings = if (selected.descriptor?.managementActivity != null)
                                    ::openAddonSettings else null,
                                featuredSettingsContent = if (selected.descriptor?.providedCapabilities
                                        ?.contains(StandardCapabilities.AppsFeatured.wireValue) == true) {
                                    {
                                        FeaturedAppSettingsUi(
                                            catalog = appCatalog.apps,
                                            featured = selectedFeatured,
                                            onSetPinned = { key, pinned ->
                                                if (selectedFeaturedBinding == null ||
                                                    homeFeaturedController.featured.value?.binding != selectedFeaturedBinding ||
                                                    selectedModuleComponent != selected.component.flattenToString()) {
                                                    Result.failure(IllegalStateException("The selected add-on changed. Reopen its settings and try again."))
                                                } else homeFeaturedController.setPinned(key, pinned, selectedFeaturedBinding)
                                            },
                                            onReorderPins = { keys ->
                                                if (selectedFeaturedBinding == null ||
                                                    homeFeaturedController.featured.value?.binding != selectedFeaturedBinding ||
                                                    selectedModuleComponent != selected.component.flattenToString()) {
                                                    Result.failure(IllegalStateException("The selected add-on changed. Reopen its settings and try again."))
                                                } else homeFeaturedController.reorderPins(keys, selectedFeaturedBinding)
                                            },
                                            onRefresh = {
                                                if (selectedFeaturedBinding != null) homeFeaturedController.refresh()
                                            },
                                        )
                                    }
                                } else null,
                                onDismiss = { selectedModuleComponent = null },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("homeSearchQuery", homeSearchQuery)
        outState.putInt("homeIntentGeneration", homeIntentGeneration)
        outState.putBoolean("settingsVisible", settingsVisible)
        outState.putString("selectedModuleComponent", selectedModuleComponent)
        outState.putString("pendingUninstallPackage", pendingUninstallPackage)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (handleModuleSettingsIntent(intent)) return
        if (intent.action == Intent.ACTION_MAIN &&
            (intent.hasCategory(Intent.CATEGORY_HOME) || intent.hasCategory(Intent.CATEGORY_LAUNCHER))) {
            settingsVisible = false
            selectedModuleComponent = null
            homeSearchQuery = ""
            if (::organization.isInitialized) organization.exitSorter()
            homeIntentGeneration++
        }
    }

    override fun onResume() {
        super.onResume()
        if (::homeRoleController.isInitialized) {
            refreshHomeRoleState()
        }
        if (::moduleRegistry.isInitialized) {
            moduleRegistry.refresh()
        }
        // The live repository receives package/profile callbacks. Its constructor
        // reconciles a cold-start cache; returning Home does not need a full scan.
        if (::homeFeaturedController.isInitialized) homeFeaturedController.refresh()
        if (::builtInMostUsed.isInitialized) builtInMostUsed.refresh()
        if (::organization.isInitialized) lifecycleScope.launch {
            val folders = organization.store.currentSnapshot().folders
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) organization.runtime.signals.start(folders)
        }
    }

    override fun onPause() {
        if (::organization.isInitialized) organization.runtime.signals.stop()
        super.onPause()
    }

    private fun openCoreUsageAccess() {
        handleAppAction {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (_: android.content.ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }
    }

    private fun handleAppAction(action: () -> Unit) {
        runCatching(action).onFailure {
            Toast.makeText(this, it.message ?: "Unable to open the app's settings.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        if (::homeFeaturedController.isInitialized) homeFeaturedController.close()
        if (::featuredAppsClient.isInitialized) featuredAppsClient.close()
        if (::homeSortController.isInitialized) homeSortController.close()
        if (::appSortClient.isInitialized) appSortClient.close()
        if (::eventSources.isInitialized) {
            eventSources.close()
        }
        if (::eventBus.isInitialized) {
            eventBus.close()
        }
        if (::contextManager.isInitialized) {
            contextManager.close()
        }
        if (::instanceStore.isInitialized) {
            instanceStore.close()
        }
        if (::compositionStore.isInitialized) {
            compositionStore.close()
        }
        if (::capabilityRouter.isInitialized) {
            capabilityRouter.close()
        }
        if (::moduleRegistry.isInitialized) {
            moduleRegistry.close()
        }
        if (::grantStore.isInitialized) {
            grantStore.close()
        }
        if (::appRepository.isInitialized) {
            appRepository.close()
        }
        super.onDestroy()
    }

    private fun activateContext(id: String) {
        lifecycleScope.launch {
            recordManagementResult("Core", "context", contextManager.activate(id).map { Unit }, "Activated context $id")
        }
    }

    private fun deactivateContext() {
        lifecycleScope.launch {
            recordManagementResult("Core", "context", contextManager.deactivate(), "Deactivated context")
        }
    }

    private fun recordManagementResult(component: String, stage: String, result: Result<Unit>, success: String) {
        val error = result.exceptionOrNull()
        val message = if (error == null) success else error.message ?: error.javaClass.simpleName
        moduleRegistry.managementStore.record(component, stage, message, error != null)
        if (error != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun handleModuleSettingsIntent(intent: Intent?): Boolean {
        if (intent?.action != ModuleContract.ACTION_OPEN_MODULE_SETTINGS) return false
        val value = intent.getStringExtra(ModuleContract.EXTRA_MODULE_COMPONENT)
            ?.takeIf { it.length <= 512 } ?: return false
        val component = ComponentName.unflattenFromString(value) ?: return false
        // Navigation only: the UI resolves this against discovered installed modules.
        selectedModuleComponent = component.flattenToString()
        settingsVisible = true
        return true
    }

    private fun openAddonSettings(module: ModuleRecord) {
        val declaration = module.descriptor?.managementActivity ?: return
        try {
            val service = ServiceInfo().apply { packageName = module.component.packageName }
            val component = ModuleDescriptorDiscovery.validateManagementActivity(packageManager, service, declaration)
            startActivity(Intent().setComponent(component))
        } catch (error: RuntimeException) {
            recordManagementResult(module.component.flattenToString(), "settings",
                Result.failure(IllegalStateException("Add-on settings could not be opened. Refresh modules or reinstall the add-on.", error)), "")
        }
    }

    private fun openFeaturedManagement(current: HomeFeaturedState, modules: List<ModuleRecord>) {
        if (homeFeaturedController.featured.value?.binding != current.binding) {
            Toast.makeText(this, "The selected add-on changed. Try again.", Toast.LENGTH_LONG).show()
            return
        }
        val component = current.binding.route.component ?: return
        val module = modules.firstOrNull { it.component == component }
        if (module?.descriptor?.managementActivity != null) {
            // The descriptor target is validated by the same path used in module Settings.
            openAddonSettings(module)
        } else {
            selectedModuleComponent = component.flattenToString()
            settingsVisible = true
        }
    }

    private fun uninstallModule(module: ModuleRecord) {
        val targetPackage = module.component.packageName
        if (targetPackage == packageName) return
        try {
            pendingUninstallPackage = targetPackage
            uninstallLauncher.launch(Intent(Intent.ACTION_DELETE, Uri.parse("package:$targetPackage")))
            moduleRegistry.managementStore.record(targetPackage, "uninstall", "Opened Android uninstall confirmation for the entire package.")
        } catch (error: RuntimeException) {
            pendingUninstallPackage = null
            recordManagementResult(targetPackage, "uninstall", Result.failure(error), "")
        }
    }

    private fun refreshHomeRoleState() {
        homeRoleAvailable =
            homeRoleController.isHomeRoleAvailable()
        homeRoleHeld =
            homeRoleController.isHomeRoleHeld()
    }

    private fun requestHomeRole() {
        homeRoleController
            .createHomeRoleRequestIntent()
            ?.let(homeRoleLauncher::launch)
    }
}


