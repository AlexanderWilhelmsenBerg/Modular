package io.github.alexanderwilhelmsenberg.modular

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.alexanderwilhelmsenberg.modular.apps.AndroidAppRepository
import io.github.alexanderwilhelmsenberg.modular.apps.AppRepository
import io.github.alexanderwilhelmsenberg.modular.home.HomeRoleController
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleRegistry
import io.github.alexanderwilhelmsenberg.modular.ui.ModularApp

class MainActivity : ComponentActivity() {
    private lateinit var homeRoleController: HomeRoleController
    private lateinit var moduleRegistry: ModuleRegistry
    private lateinit var appRepository: AppRepository

    private var homeRoleAvailable by mutableStateOf(false)
    private var homeRoleHeld by mutableStateOf(false)

    private val homeRoleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshHomeRoleState()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        homeRoleController = HomeRoleController(this)
        moduleRegistry = ModuleRegistry(this)
        appRepository = AndroidAppRepository(this)

        refreshHomeRoleState()
        moduleRegistry.refresh()
        appRepository.refresh()

        setContent {
            val modules by moduleRegistry.modules.collectAsState()
            val appCatalog by appRepository.catalog.collectAsState()

            ModularApp(
                homeRoleAvailable = homeRoleAvailable,
                homeRoleHeld = homeRoleHeld,
                modules = modules,
                appCatalog = appCatalog,
                onRequestHomeRole = ::requestHomeRole,
                onRefreshModules = moduleRegistry::refresh,
                onRefreshApps = appRepository::refresh,
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
        if (::appRepository.isInitialized) {
            appRepository.close()
        }
        if (::moduleRegistry.isInitialized) {
            moduleRegistry.close()
        }
        super.onDestroy()
    }

    private fun refreshHomeRoleState() {
        homeRoleAvailable = homeRoleController.isHomeRoleAvailable()
        homeRoleHeld = homeRoleController.isHomeRoleHeld()
    }

    private fun requestHomeRole() {
        homeRoleController.createHomeRoleRequestIntent()?.let(homeRoleLauncher::launch)
    }
}
