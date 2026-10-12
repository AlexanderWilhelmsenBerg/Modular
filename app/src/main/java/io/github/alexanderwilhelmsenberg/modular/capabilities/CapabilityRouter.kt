package io.github.alexanderwilhelmsenberg.modular.capabilities

import android.os.IBinder
import io.github.alexanderwilhelmsenberg.modular.moduleapi.CapabilityId
import io.github.alexanderwilhelmsenberg.modular.moduleapi.CoreCapabilities
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleConnectionStatus
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Resolves exact major-version capability contracts to available providers.
 *
 * Selection policy is intentionally minimal in Slice 3: Core providers are
 * registered first and invokable external providers are exposed as candidates.
 * Instance/context policy will choose among multiple external providers later.
 */
class CapabilityRouter(
    private val moduleRegistry: ModuleRegistry,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _routes = MutableStateFlow(coreRoutes())
    val routes: StateFlow<List<CapabilityRoute>> = _routes.asStateFlow()

    init {
        scope.launch {
            moduleRegistry.modules.collect { modules ->
                val moduleRoutes = modules
                    .asSequence()
                    .filter {
                        it.status == ModuleConnectionStatus.CONNECTED &&
                            it.sessionId != null
                    }
                    .flatMap { module ->
                        module.capabilities
                            .asSequence()
                            .mapNotNull(CapabilityId::parse)
                            .map { capability ->
                                CapabilityRoute(
                                    capability = capability,
                                    providerKind = CapabilityProviderKind.MODULE,
                                    providerId = module.moduleId
                                        ?: module.component.flattenToShortString(),
                                    component = module.component,
                                )
                            }
                    }
                    .sortedWith(
                        compareBy(
                            { it.capability.wireValue },
                            { it.providerId },
                        ),
                    )
                    .toList()

                _routes.value = coreRoutes() + moduleRoutes
            }
        }
    }

    fun providersFor(capability: CapabilityId): List<CapabilityRoute> =
        routes.value.filter { it.capability == capability }

    fun resolveCore(capability: CapabilityId): CapabilityRoute? =
        routes.value.firstOrNull {
            it.capability == capability &&
                it.providerKind == CapabilityProviderKind.CORE
        }

    suspend fun acquireModuleBinder(
        route: CapabilityRoute,
    ): Result<IBinder> {
        if (route.providerKind != CapabilityProviderKind.MODULE) {
            return Result.failure(
                IllegalArgumentException("Route is not an external module"),
            )
        }

        val component = route.component
            ?: return Result.failure(
                IllegalStateException("Module route has no component"),
            )

        return moduleRegistry.acquireCapabilityBinder(
            component = component,
            capability = route.capability,
        )
    }

    override fun close() {
        scope.cancel()
    }

    private fun coreRoutes(): List<CapabilityRoute> =
        CoreCapabilities.all.map { capability ->
            CapabilityRoute(
                capability = capability,
                providerKind = CapabilityProviderKind.CORE,
                providerId = CORE_PROVIDER_ID,
            )
        }

    companion object {
        const val CORE_PROVIDER_ID = "modular.core"
    }
}
