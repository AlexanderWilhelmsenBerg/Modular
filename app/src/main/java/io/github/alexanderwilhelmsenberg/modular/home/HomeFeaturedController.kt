package io.github.alexanderwilhelmsenberg.modular.home

import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityProviderKind
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityRoute
import io.github.alexanderwilhelmsenberg.modular.capabilities.FeaturedAppsClient
import io.github.alexanderwilhelmsenberg.modular.composition.ActiveContextState
import io.github.alexanderwilhelmsenberg.modular.composition.ResolvedContextBinding
import io.github.alexanderwilhelmsenberg.modular.moduleapi.FeaturedAppsContract
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppKeyParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortItemParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedAppsRequestParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedAppsResultParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedMutationResultParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedParcelBounds
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedPinOrderRequestParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedPinRequestParcel
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** A validated Core-owned starred section. Missing saved pins are management-only. */
data class HomeFeaturedState(
    val binding: ResolvedContextBinding,
    val pinnedApps: List<LaunchableApp>,
    val suggestedApps: List<LaunchableApp>,
    val pinnedKeys: List<AppKey>,
    val unavailablePinnedKeys: List<AppKey>,
    val featureState: Int,
    val featureRevision: Long,
    val message: String? = null,
) {
    val featuredApps: List<LaunchableApp> get() = pinnedApps + suggestedApps
}

/** Reads one selected featured provider without affecting Core's complete app catalog. */
class HomeFeaturedController(
    private val catalog: StateFlow<AppCatalog>,
    private val activeContext: StateFlow<ActiveContextState>,
    private val routes: StateFlow<List<CapabilityRoute>>,
    private val client: FeaturedAppsClient,
    localeTag: String = Locale.getDefault().toLanguageTag(),
    private val timeoutMillis: Long = 1_500L,
    private val onFailure: (CapabilityRoute, String) -> Unit = { _, _ -> },
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : AutoCloseable {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val workerScope = CoroutineScope(scope.coroutineContext + job)
    private val localeTags = MutableStateFlow(localeTag)
    private val refreshes = MutableStateFlow(0L)
    private val serial = AtomicLong()
    private val mutationMutex = Mutex()
    private val _featured = MutableStateFlow<HomeFeaturedState?>(null)
    val featured: StateFlow<HomeFeaturedState?> = _featured.asStateFlow()

    init {
        require(timeoutMillis > 0)
        workerScope.launch {
            combine(catalog, activeContext, routes, localeTags, refreshes) { apps, context, available, locale, refresh ->
                Snapshot(apps, context, available, locale, refresh)
            }.collectLatest { snapshot ->
                val token = serial.incrementAndGet()
                _featured.value = null
                val binding = selectedBinding(snapshot) ?: return@collectLatest
                try {
                    read(snapshot, binding, token)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    report(binding.route, "Featured apps are unavailable. Try again in Settings.")
                }
            }
        }
    }

    fun setLocaleTag(tag: String) { localeTags.value = tag }
    fun refresh() { refreshes.value += 1 }

    suspend fun setPinned(key: AppKey, pinned: Boolean): Result<Unit> = setPinned(key, pinned, null)

    suspend fun setPinned(
        key: AppKey,
        pinned: Boolean,
        expectedBinding: ResolvedContextBinding?,
    ): Result<Unit> = mutationMutex.withLock {
        runCatching {
            val snapshot = snapshot()
            val binding = selectedBinding(snapshot) ?: error("Featured provider is unavailable")
            require(expectedBinding == null || binding == expectedBinding) {
                "Selected featured provider changed; reopen its settings"
            }
            val current = _featured.value ?: error("Featured apps are still loading")
            require(current.binding == binding) { "Featured apps changed; refresh and try again" }
            val present = snapshot.catalog.apps.any { it.key == key && it.enabled } &&
                !isQuiet(snapshot.catalog, key)
            require(if (pinned) present else key in current.pinnedKeys) {
                if (pinned) "App is no longer in the catalog" else "App is not pinned"
            }
            if (pinned && key !in current.pinnedKeys) require(current.pinnedKeys.size < FeaturedParcelBounds.MAX_PINS) {
                "Pin limit reached"
            }
            val request = FeaturedPinRequestParcel(
                requestId = UUID.randomUUID().toString(),
                catalogRevision = snapshot.catalog.generation,
                expectedFeatureRevision = current.featureRevision,
                key = key.toParcel(), pinned = pinned,
            )
            FeaturedAppsContract.validatePinRequest(request)
            val response = withTimeoutOrNull(timeoutMillis) { client.setPinned(binding.route, binding, request) }
                ?: error("Pin save timed out")
            val result = response.getOrThrow()
            FeaturedAppsContract.validateMutationResult(request.requestId, result)
            require(result.status == FeaturedMutationResultParcel.STATUS_SUCCESS) {
                if (result.status == FeaturedMutationResultParcel.STATUS_CONFLICT) "Pins changed; refresh and try again"
                else "Could not save pin"
            }
            require(result.featureRevision >= current.featureRevision) { "Stale pin acknowledgment" }
            require(stillCurrent(snapshot, binding)) { "Featured provider changed during pin save" }
            val token = serial.incrementAndGet()
            read(snapshot, binding, token, result.featureRevision).getOrThrow()
        }.onFailure { if (it is CancellationException) throw it }
    }

    suspend fun reorderPins(keys: List<AppKey>): Result<Unit> = reorderPins(keys, null)

    suspend fun reorderPins(
        keys: List<AppKey>,
        expectedBinding: ResolvedContextBinding?,
    ): Result<Unit> = mutationMutex.withLock {
        runCatching {
            val snapshot = snapshot()
            val binding = selectedBinding(snapshot) ?: error("Featured provider is unavailable")
            require(expectedBinding == null || binding == expectedBinding) {
                "Selected featured provider changed; reopen its settings"
            }
            val current = _featured.value ?: error("Featured apps are still loading")
            require(current.binding == binding) { "Featured apps changed; refresh and try again" }
            val request = FeaturedPinOrderRequestParcel(
                requestId = UUID.randomUUID().toString(),
                expectedFeatureRevision = current.featureRevision,
                orderedKeys = keys.map { it.toParcel() },
            )
            FeaturedAppsContract.validatePinOrder(request, current.pinnedKeys.map { it.toParcel() })
            val response = withTimeoutOrNull(timeoutMillis) { client.replacePinOrder(binding.route, binding, request) }
                ?: error("Pin order save timed out")
            val result = response.getOrThrow()
            FeaturedAppsContract.validateMutationResult(request.requestId, result)
            require(result.status == FeaturedMutationResultParcel.STATUS_SUCCESS) {
                if (result.status == FeaturedMutationResultParcel.STATUS_CONFLICT) "Pins changed; refresh and try again"
                else "Could not save pin order"
            }
            require(result.featureRevision >= current.featureRevision) { "Stale pin order acknowledgment" }
            require(stillCurrent(snapshot, binding)) { "Featured provider changed during pin order save" }
            val token = serial.incrementAndGet()
            read(snapshot, binding, token, result.featureRevision).getOrThrow()
        }.onFailure { if (it is CancellationException) throw it }
    }

    override fun close() { job.cancel() }

    private suspend fun read(
        snapshot: Snapshot,
        binding: ResolvedContextBinding,
        token: Long,
        minimumFeatureRevision: Long = 0L,
    ): Result<Unit> = runCatching {
        val source = snapshot.catalog.apps
        require(source.size <= FeaturedAppsRequestParcel.MAX_ITEMS) { "Catalog exceeds featured provider limit" }
        val request = FeaturedAppsRequestParcel(
            requestId = UUID.randomUUID().toString(),
            catalogRevision = snapshot.catalog.generation,
            localeTag = snapshot.localeTag.take(FeaturedParcelBounds.MAX_LOCALE_TAG_LENGTH),
            items = source.map { AppSortItemParcel(it.key.toParcel(), it.label.take(FeaturedParcelBounds.MAX_LABEL_LENGTH)) },
        )
        FeaturedAppsContract.validateRequest(request)
        val response = withTimeoutOrNull(timeoutMillis) { client.getFeaturedApps(binding.route, binding, request) }
            ?: error("Featured provider timed out")
        val result = response.getOrThrow()
        require(result.featureRevision >= minimumFeatureRevision) { "Featured provider returned stale pins" }
        currentCoroutineContext().ensureActive()
        require(token == serial.get() && stillCurrent(snapshot, binding)) { "Featured response is stale" }
        _featured.value = validatedState(snapshot.catalog, binding, request, result)
    }.onFailure { error ->
        if (error is CancellationException) throw error
        if (error !is CancellationException && token == serial.get() && stillCurrent(snapshot, binding)) {
            report(binding.route, if (error.message == "Catalog exceeds featured provider limit")
                "Featured apps are unavailable with this catalog size."
                else "Featured apps are unavailable. Try again in Settings.")
        }
    }

    private fun selectedBinding(snapshot: Snapshot): ResolvedContextBinding? = snapshot.context
        .takeIf { it.isReady && it.resolvedBindings.none { binding ->
            binding.capability == StandardCapabilities.AppsSort
        } }
        ?.resolvedBindings
        ?.firstOrNull { it.capability == StandardCapabilities.AppsFeatured }
        ?.takeIf { it.route.providerKind == CapabilityProviderKind.MODULE && it.route in snapshot.routes }

    private fun snapshot() = Snapshot(catalog.value, activeContext.value, routes.value, localeTags.value, refreshes.value)
    private fun stillCurrent(snapshot: Snapshot, binding: ResolvedContextBinding): Boolean =
        catalog.value == snapshot.catalog && activeContext.value == snapshot.context &&
            routes.value == snapshot.routes && localeTags.value == snapshot.localeTag &&
            selectedBinding(snapshot) == binding

    private fun report(route: CapabilityRoute, message: String) { runCatching { onFailure(route, message) } }

    private data class Snapshot(
        val catalog: AppCatalog,
        val context: ActiveContextState,
        val routes: List<CapabilityRoute>,
        val localeTag: String,
        val refresh: Long,
    )

    companion object {
        /** Keeps provider keys separate from Core-owned labels and launch metadata. */
        internal fun validatedState(
            catalog: AppCatalog,
            binding: ResolvedContextBinding,
            request: FeaturedAppsRequestParcel,
            result: FeaturedAppsResultParcel,
        ): HomeFeaturedState {
            require(request.catalogRevision == catalog.generation &&
                request.items.map { it.key } == catalog.apps.map { it.key.toParcel() }) {
                "Featured request does not match current catalog"
            }
            FeaturedAppsContract.validateResult(request, result)
            require(result.status == FeaturedAppsResultParcel.STATUS_SUCCESS) { "Featured provider failed" }
            require(result.featureState == FeaturedAppsResultParcel.STATE_READY || result.suggestedKeys.isEmpty()) {
                "Unavailable usage state returned suggestions"
            }
            val byKey = catalog.apps.associateBy { it.key }
            val pins = result.pinnedKeys.map { it.toKey() }
            val suggestions = result.suggestedKeys.map { it.toKey() }
            return HomeFeaturedState(
                binding = binding,
                pinnedApps = pins.mapNotNull(byKey::get).filter { it.enabled && !isQuiet(catalog, it.key) },
                suggestedApps = suggestions.mapNotNull(byKey::get).filter { it.enabled && !isQuiet(catalog, it.key) },
                pinnedKeys = pins,
                unavailablePinnedKeys = pins.filter { key -> byKey[key]?.let { !it.enabled || isQuiet(catalog, key) } ?: true },
                featureState = result.featureState,
                featureRevision = result.featureRevision,
                message = result.message,
            )
        }

        private fun isQuiet(catalog: AppCatalog, key: AppKey): Boolean =
            catalog.profiles.any { it.serialNumber == key.profileSerial && it.quietModeEnabled }
    }
}

private fun AppKey.toParcel() = AppKeyParcel(packageName, activityName, profileSerial)
private fun AppKeyParcel.toKey() = AppKey(packageName, activityName, profileSerial)
