package io.github.alexanderwilhelmsenberg.modular

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.github.alexanderwilhelmsenberg.modular.apps.AndroidAppRepository
import io.github.alexanderwilhelmsenberg.modular.apps.AppRepository
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityRouter
import io.github.alexanderwilhelmsenberg.modular.capabilities.CoreHostFactory
import io.github.alexanderwilhelmsenberg.modular.composition.ContextManager
import io.github.alexanderwilhelmsenberg.modular.composition.DataStoreCompositionStateStore
import io.github.alexanderwilhelmsenberg.modular.composition.InstanceStore
import io.github.alexanderwilhelmsenberg.modular.home.HomeRoleController
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleRegistry
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

        val hostFactory = CoreHostFactory(
            context = this,
            appRepository = appRepository,
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

            ModularApp(
                homeRoleAvailable = homeRoleAvailable,
                homeRoleHeld = homeRoleHeld,
                modules = modules,
                appCatalog = appCatalog,
                instances = instances,
                contexts = contexts,
                activeContext = activeContext,
                compositionLoadError = compositionLoadError,
                contextActivationError = activationError,
                onRequestHomeRole = ::requestHomeRole,
                onRefreshModules = moduleRegistry::refresh,
                onRefreshApps = appRepository::refresh,
                onActivateContext = { contextId ->
                    lifecycleScope.launch {
                        contextManager.activate(contextId)
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
        if (::appRepository.isInitialized) {
            appRepository.close()
        }
        super.onDestroy()
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
