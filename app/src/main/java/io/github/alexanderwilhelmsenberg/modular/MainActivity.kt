package io.github.alexanderwilhelmsenberg.modular

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
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
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.SurfaceDocumentParcel
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleRegistry
import io.github.alexanderwilhelmsenberg.modular.security.AndroidModuleIdentityResolver
import io.github.alexanderwilhelmsenberg.modular.security.DataStoreModuleGrantStateStore
import io.github.alexanderwilhelmsenberg.modular.security.ModuleSecurityManager
import io.github.alexanderwilhelmsenberg.modular.security.OfficialModuleBootstrapPolicy
import io.github.alexanderwilhelmsenberg.modular.surface.HomeSurfaceClient
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
        capabilityRouter = CapabilityRouter(moduleRegistry)

        compositionStore =
            DataStoreCompositionStateStore(this)
        instanceStore = InstanceStore(compositionStore)
        contextManager = ContextManager(
            stateStore = compositionStore,
            routes = capabilityRouter.routes,
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

        setContent {
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

            val homeSurfaceClient = remember {
                HomeSurfaceClient(capabilityRouter)
            }
            val homeBinding =
                activeContext.resolvedBindings
                    .firstOrNull { binding ->
                        binding.capability ==
                            StandardCapabilities
                                .SurfaceHome
                    }
            var homeSurface by remember(
                homeBinding?.instance?.id,
                homeBinding?.route?.providerId,
            ) {
                mutableStateOf<SurfaceDocumentParcel?>(
                    null,
                )
            }
            var homeSurfaceError by remember(
                homeBinding?.instance?.id,
                homeBinding?.route?.providerId,
            ) {
                mutableStateOf<String?>(null)
            }
            var homeSurfaceRefresh by remember {
                mutableStateOf(0L)
            }

            LaunchedEffect(
                homeBinding?.instance?.id,
                homeBinding?.route?.providerId,
                homeSurfaceRefresh,
            ) {
                val binding = homeBinding

                if (binding == null) {
                    homeSurface = null
                    homeSurfaceError = null
                } else {
                    homeSurfaceClient.render(
                        route = binding.route,
                        instanceId =
                            binding.instance.id,
                    ).fold(
                        onSuccess = { document ->
                            homeSurface = document
                            homeSurfaceError = null
                        },
                        onFailure = { throwable ->
                            homeSurface = null
                            homeSurfaceError =
                                throwable.message
                                    ?: throwable::class
                                        .java.simpleName
                        },
                    )
                }
            }

            val securityStatuses =
                modules.map { module ->
                    val requested =
                        module.requestedCoreCapabilities
                            .mapNotNull {
                                wireValue ->
                                CapabilityId.parse(
                                    wireValue,
                                )
                            }
                            .toSet()

                    securityManager.status(
                        packageName =
                            module.component
                                .packageName,
                        requestedCapabilities =
                            requested,
                    )
                }

            ModularApp(
                homeRoleAvailable = homeRoleAvailable,
                homeRoleHeld = homeRoleHeld,
                modules = modules,
                appCatalog = appCatalog,
                instances = instances,
                contexts = contexts,
                activeContext = activeContext,
                compositionLoadError =
                    compositionLoadError,
                contextActivationError =
                    activationError,
                eventBusDiagnostics =
                    eventDiagnostics,
                moduleSecurity = securityStatuses,
                grantRecordCount =
                    grantState.records.size,
                grantLoadError = grantLoadError,
                homeSurface = homeSurface,
                homeSurfaceError =
                    homeSurfaceError,
                onHomeSurfaceAction = { actionId ->
                    val binding = homeBinding

                    if (binding != null) {
                        lifecycleScope.launch {
                            homeSurfaceClient
                                .performAction(
                                    route =
                                        binding.route,
                                    instanceId =
                                        binding.instance
                                            .id,
                                    actionId =
                                        actionId,
                                )
                                .fold(
                                    onSuccess = {
                                        homeSurfaceRefresh +=
                                            1L
                                    },
                                    onFailure = {
                                        throwable ->
                                        homeSurfaceError =
                                            throwable
                                                .message
                                                ?: throwable::
                                                    class
                                                    .java
                                                    .simpleName
                                    },
                                )
                        }
                    }
                },
                onRequestHomeRole =
                    ::requestHomeRole,
                onRefreshModules =
                    moduleRegistry::refresh,
                onRefreshApps =
                    appRepository::refresh,
                onActivateContext = {
                    contextId ->
                    lifecycleScope.launch {
                        contextManager.activate(
                            contextId,
                        )
                    }
                },
                onDeactivateContext = {
                    lifecycleScope.launch {
                        contextManager.deactivate()
                    }
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()

        if (
            ::homeRoleController.isInitialized
        ) {
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

    private fun refreshHomeRoleState() {
        homeRoleAvailable =
            homeRoleController
                .isHomeRoleAvailable()
        homeRoleHeld =
            homeRoleController.isHomeRoleHeld()
    }

    private fun requestHomeRole() {
        homeRoleController
            .createHomeRoleRequestIntent()
            ?.let(homeRoleLauncher::launch)
    }
}
