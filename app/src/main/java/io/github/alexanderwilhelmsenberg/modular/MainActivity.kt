package io.github.alexanderwilhelmsenberg.modular

import android.os.Bundle
import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.github.alexanderwilhelmsenberg.modular.appearance.AppearanceSettingsStore
import io.github.alexanderwilhelmsenberg.modular.appearance.ModularTheme
import io.github.alexanderwilhelmsenberg.modular.appearance.applySystemBarAppearance
import io.github.alexanderwilhelmsenberg.modular.capabilities.AppSortClient
import io.github.alexanderwilhelmsenberg.modular.home.HomeSortController
import io.github.alexanderwilhelmsenberg.modular.apps.AndroidAppRepository
import io.github.alexanderwilhelmsenberg.modular.apps.AppRepository
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityRouter
import io.github.alexanderwilhelmsenberg.modular.capabilities.CoreHostFactory
import io.github.alexanderwilhelmsenberg.modular.composition.ContextManager
import io.github.alexanderwilhelmsenberg.modular.composition.DataStoreCompositionStateStore
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
import io.github.alexanderwilhelmsenberg.modular.ui.ModularApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var homeRoleController: HomeRoleController
    private lateinit var moduleRegistry: ModuleRegistry
    private lateinit var capabilityRouter: CapabilityRouter
    private lateinit var appRepository: AppRepository
    private lateinit var compositionStore:
        DataStoreCompositionStateStore
    private lateinit var instanceStore: InstanceStore
    private lateinit var contextManager: ContextManager
    private lateinit var eventBus: CoreEventBus
    private lateinit var eventSources: CoreEventSources
    private lateinit var grantStore:
        DataStoreModuleGrantStateStore
    private lateinit var securityManager:
        ModuleSecurityManager

    private lateinit var appearanceStore: AppearanceSettingsStore
    private lateinit var appSortClient: AppSortClient
    private lateinit var homeSortController: HomeSortController
    private var homeSearchQuery by mutableStateOf("")
    private var homeIntentGeneration by mutableStateOf(0)

    private var settingsVisible by mutableStateOf(false)
    private var pendingUninstallPackage: String? = null

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
        appearanceStore = AppearanceSettingsStore(this)
        settingsVisible = savedInstanceState?.getBoolean("settingsVisible") ?: false
        pendingUninstallPackage = savedInstanceState?.getString("pendingUninstallPackage")

        homeRoleController = HomeRoleController(this)
        appRepository = AndroidAppRepository(this)
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
        instanceStore = InstanceStore(compositionStore)
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
        appRepository.refresh()

        lifecycleScope.launch {
            // Read local appearance before the first Compose frame, without blocking Main.
            val initialTheme = appearanceStore.currentTheme()
            applySystemBarAppearance(this@MainActivity, initialTheme)
            setContent {
                val appearanceTheme by appearanceStore.theme.collectAsState(initial = initialTheme)
                val appearanceError by appearanceStore.error.collectAsState()
                val homeOrderedApps by homeSortController.orderedApps.collectAsState()
                LaunchedEffect(appearanceTheme) {
                    applySystemBarAppearance(this@MainActivity, appearanceTheme)
                }
                ModularTheme(appearanceTheme) {
                    val modules by moduleRegistry.modules.collectAsState()
                    val appCatalog by appRepository.catalog.collectAsState()
                    val instances by instanceStore.instances.collectAsState()
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

                    val homeListState = rememberLazyListState()
                    BackHandler { settingsVisible = false }

                    if (!settingsVisible) {
                        key(homeIntentGeneration) {
                            HomeScreen(
                                catalog = appCatalog,
                                orderedApps = homeOrderedApps,
                                searchQuery = homeSearchQuery,
                                onSearchQueryChange = { homeSearchQuery = it },
                                listState = homeListState,
                                showMakeDefault = homeRoleAvailable && !homeRoleHeld,
                                onLaunch = { app ->
                                    lifecycleScope.launch {
                                        appRepository.launch(app.key).onFailure {
                                            Toast.makeText(this@MainActivity, "Unable to open ${app.label}", Toast.LENGTH_LONG).show()
                                            appRepository.refresh()
                                        }
                                    }
                                },
                                onRefresh = appRepository::refresh,
                                onMakeDefault = ::requestHomeRole,
                                onOpenSettings = { settingsVisible = true },
                            )
                        }
                    } else {
                        val securityStatuses = modules.groupBy { it.component.packageName }.map { (packageName, packageModules) ->
                            securityManager.status(
                                packageName = packageName,
                                requestedCapabilities = packageModules.flatMap { it.requestedCoreCapabilities }
                                    .mapNotNull(CapabilityId::parse).toSet(),
                            )
                        }

                        ModularApp(
                            appearanceTheme = appearanceTheme,
                            appearanceError = appearanceError,
                            onAppearanceThemeSelected = { selected ->
                                lifecycleScope.launch { appearanceStore.setTheme(selected) }
                            },
                            onBack = { settingsVisible = false },
                            corePackageName = packageName,
                            managementEvents = managementEvents,
                            onSetModuleEnabled = { module, enabled -> moduleRegistry.setEnabled(module.component, enabled) },
                            onRetryModule = { module -> moduleRegistry.retry(module.component) },
                            onUninstallModule = ::uninstallModule,
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
                                CompositionManagement(
                                    instances = instances,
                                    contexts = contexts,
                                    modules = modules,
                                    activeContext = activeContext,
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
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("homeSearchQuery", homeSearchQuery)
        outState.putBoolean("settingsVisible", settingsVisible)
        outState.putString("pendingUninstallPackage", pendingUninstallPackage)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_MAIN &&
            (intent.hasCategory(Intent.CATEGORY_HOME) || intent.hasCategory(Intent.CATEGORY_LAUNCHER))) {
            settingsVisible = false
            homeSearchQuery = ""
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
        if (::appRepository.isInitialized) {
            appRepository.refresh()
        }
    }

    override fun onDestroy() {
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


