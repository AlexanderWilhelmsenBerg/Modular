package io.github.alexanderwilhelmsenberg.modular.home

import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityProviderKind
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityRoute
import io.github.alexanderwilhelmsenberg.modular.composition.ActiveContextState
import io.github.alexanderwilhelmsenberg.modular.composition.ResolvedContextBinding
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import android.os.RemoteException
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Core's Home order, with an optional selected apps.sort/v1 helper. */
class HomeSortController(
    private val catalog: StateFlow<AppCatalog>,
    private val activeContext: StateFlow<ActiveContextState>,
    private val routes: StateFlow<List<CapabilityRoute>>,
    private val sort: suspend (CapabilityRoute, ResolvedContextBinding, List<LaunchableApp>, String) -> Result<List<LaunchableApp>>,
    localeTag: String = Locale.getDefault().toLanguageTag(),
    private val timeoutMillis: Long = 1_500L,
    private val onFailure: (CapabilityRoute, String) -> Unit = { _, _ -> },
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : AutoCloseable {
    private val job: Job = SupervisorJob(scope.coroutineContext[Job])
    private val workerScope = CoroutineScope(scope.coroutineContext + job)
    private val localeTags = MutableStateFlow(localeTag)
    private val _orderedApps = MutableStateFlow<List<LaunchableApp>>(emptyList())
    val orderedApps: StateFlow<List<LaunchableApp>> = _orderedApps.asStateFlow()
    private val _customOrderActive = MutableStateFlow(false)
    val customOrderActive: StateFlow<Boolean> = _customOrderActive.asStateFlow()
    private var failedBinding: Pair<CapabilityRoute, String>? = null

    init {
        require(timeoutMillis > 0) { "Sort timeout must be positive" }
        workerScope.launch {
            combine(catalog, activeContext, routes, localeTags) { apps, context, available, locale ->
                Snapshot(apps, context, available, locale)
            }.collectLatest { snapshot ->
                val source = snapshot.catalog.apps
                _customOrderActive.value = false
                _orderedApps.value = alphabetical(source, snapshot.localeTag)

                val binding = snapshot.activeContext
                    .takeIf { it.isReady }
                    ?.resolvedBindings
                    ?.firstOrNull { it.capability == StandardCapabilities.AppsSort }
                    ?.takeIf { it.route.providerKind == CapabilityProviderKind.MODULE && it.route in snapshot.routes }
                    ?: run {
                        failedBinding = null
                        return@collectLatest
                    }
                val route = binding.route

                if (source.isEmpty()) return@collectLatest

                val response = try {
                    withTimeoutOrNull(timeoutMillis) {
                        sort(route, binding, source, snapshot.localeTag)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Result.failure(error)
                }

                currentCoroutineContext().ensureActive()
                if (catalog.value == snapshot.catalog &&
                    activeContext.value == snapshot.activeContext &&
                    routes.value == snapshot.routes &&
                    localeTags.value == snapshot.localeTag) {
                    val result = response?.getOrNull()
                    if (result == null) {
                        reportFailure(route, binding, if (response == null) {
                            "Sort helper timed out. Retry its connection in Settings."
                        } else {
                            "Sort helper failed (${failureCategory(response.exceptionOrNull())}). " +
                                "Retry its connection in Settings."
                        })
                        return@collectLatest
                    }
                    // Re-map keys to the current Core objects even if a faulty
                    // client returned altered labels or launch metadata.
                    val original = originalAppsInOrder(source, result)
                    if (original == null) reportFailure(
                        route,
                        binding,
                        "Sort helper returned an invalid app order. Retry its connection in Settings.",
                    )
                    else {
                        failedBinding = null
                        _orderedApps.value = original
                        _customOrderActive.value = true
                    }
                }
            }
        }
    }

    fun setLocaleTag(localeTag: String) {
        localeTags.value = localeTag
    }

    override fun close() {
        job.cancel()
    }

    private fun reportFailure(route: CapabilityRoute, binding: ResolvedContextBinding, message: String) {
        val key = route to (binding.instance.id + "\u0000" + binding.instance.configurationJson)
        if (failedBinding == key) return
        failedBinding = key
        runCatching { onFailure(route, message) }
    }

    private fun failureCategory(error: Throwable?): String = when (error) {
        is SecurityException -> "permission denied"
        is RemoteException -> "connection error"
        is IllegalArgumentException -> "invalid response"
        is IllegalStateException -> "unavailable"
        else -> "call error"
    }

    private data class Snapshot(
        val catalog: AppCatalog,
        val activeContext: ActiveContextState,
        val routes: List<CapabilityRoute>,
        val localeTag: String,
    )

    companion object {
        fun alphabetical(apps: List<LaunchableApp>, localeTag: String): List<LaunchableApp> {
            val collator = Collator.getInstance(Locale.forLanguageTag(localeTag))
            collator.strength = Collator.PRIMARY
            return apps.sortedWith { left, right ->
                val byLabel = collator.compare(left.label, right.label)
                if (byLabel != 0) byLabel else left.key.stableId.compareTo(right.key.stableId)
            }
        }

        fun originalAppsInOrder(
            source: List<LaunchableApp>,
            candidate: List<LaunchableApp>,
        ): List<LaunchableApp>? {
            if (source.size != candidate.size) return null
            val byKey = source.associateBy { it.key }
            if (byKey.size != source.size) return null
            val seen = HashSet<AppKey>(source.size)
            return candidate.map { app ->
                if (!seen.add(app.key)) return null
                byKey[app.key] ?: return null
            }
        }
    }
}
