package io.github.alexanderwilhelmsenberg.modular.mostused

import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.apps.AppCustomizations
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.AppRepository
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedAppsResultParcel
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BuiltInMostUsedState(
    val settings: MostUsedSettings = MostUsedSettings(),
    val featuredApps: List<LaunchableApp> = emptyList(),
    val pinnedKeys: List<AppKey> = emptyList(),
    val unavailablePinnedKeys: List<AppKey> = emptyList(),
    val featureState: Int = FeaturedAppsResultParcel.STATE_NO_HISTORY,
    val hasUsageAccess: Boolean = false,
    val message: String? = null,
    val loading: Boolean = false,
    val effectiveSource: MostUsedSource = MostUsedSource.LOCAL,
    val usingLocalFallback: Boolean = false,
    val historyError: String? = null,
)

/** Native featured-app engine. It reads raw catalog entries and never binds to an add-on. */
class BuiltInMostUsedController(
    private val repository: AppRepository,
    private val store: MostUsedSettingsStore,
    private val usageSource: MostUsedUsageSource,
    scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val displayCustomizations: Flow<AppCustomizations> = flowOf(AppCustomizations()),
    private val localHistory: LocalMostUsedHistoryStore? = null,
) {
    private val refreshSignal = MutableStateFlow(0L)
    private val mutableState = MutableStateFlow(BuiltInMostUsedState())
    val state: StateFlow<BuiltInMostUsedState> = mutableState.asStateFlow()

    init {
        scope.launch {
            try {
                val historyFlow = localHistory?.history
                    ?: flowOf(LocalMostUsedHistory.empty(System::currentTimeMillis))
                val historyAndError = combine(historyFlow, localHistory?.error ?: flowOf(null)) { history, error ->
                    history to error
                }
                val baseFlow = combine(repository.catalog, store.settings, store.error, refreshSignal) {
                    catalog, settings, error, _ -> Triple(catalog, settings, error)
                }
                combine(baseFlow, displayCustomizations, historyAndError) { base, customizations, history ->
                    Snapshot(base.first, base.second, base.third, customizations, history.first, history.second)
                }.collectLatest { snapshot ->
                    calculate(snapshot.catalog, snapshot.settings, snapshot.error, snapshot.customizations,
                        snapshot.history, snapshot.historyError)
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = mutableState.value.copy(
                    message = "Most Used settings are unavailable.", loading = false,
                )
            }
        }
    }

    /** Call from activity onResume so permission revocation and new history are observed. */
    fun refresh() { refreshSignal.value += 1L }

    /** Home, search, and star launches use this path so only successful launches are learned. */
    suspend fun launchFromHome(key: AppKey): Result<Unit> {
        val launched = repository.launch(key)
        if (launched.isSuccess) recordSuccessfulLaunch(key)
        return launched
    }

    /** Recording is best-effort; callers must not fail a successful launch when storage fails. */
    suspend fun recordSuccessfulLaunch(key: AppKey): Result<Unit> {
        if (key.packageName == usageSource.corePackageName) return Result.success(Unit)
        val enabled = try { store.currentSettings().enabled } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            false
        }
        if (!enabled) return Result.success(Unit)
        return localHistory?.recordLaunch(key) ?: Result.success(Unit)
    }

    suspend fun resetLearnedUsage(): Result<Unit> =
        localHistory?.reset() ?: Result.success(Unit)

    suspend fun setPinned(key: AppKey, pinned: Boolean): Result<Unit> =
        store.setPinned(key, pinned, usableApps(repository.catalog.value).mapTo(HashSet()) { it.key })

    suspend fun reorderPins(keys: List<AppKey>): Result<Unit> = store.reorderPins(keys)

    private suspend fun calculate(
        catalog: AppCatalog,
        settings: MostUsedSettings,
        settingsError: String?,
        customizations: AppCustomizations,
        history: LocalMostUsedHistory,
        historyError: String?,
    ) {
        val apps = customizations.visibleApps(usableApps(catalog))
        val available = apps.associateBy { it.key }
        val pins = settings.pinnedKeys.mapNotNull(available::get)
        val unavailable = settings.pinnedKeys.filterNot(available::containsKey)
        val initial = BuiltInMostUsedState(
            settings = settings,
            pinnedKeys = settings.pinnedKeys,
            unavailablePinnedKeys = unavailable,
            featuredApps = if (settings.enabled) pins else emptyList(),
            loading = settings.enabled,
            effectiveSource = settings.source,
            historyError = historyError,
        )
        mutableState.value = initial
        // AppOps status remains accurate for an explicitly selected Android source,
        // including when only pins are shown. Local mode never asks AppOps.
        val access = settings.source == MostUsedSource.ANDROID && accessOrFalse()
        val base = initial.copy(hasUsageAccess = access)
        if (settingsError != null) {
            mutableState.value = base.copy(message = settingsError, loading = false)
            return
        }
        if (!settings.enabled) {
            mutableState.value = base.copy(loading = false)
            return
        }
        if (settings.suggestedCount == 0) {
            mutableState.value = base.copy(featureState = FeaturedAppsResultParcel.STATE_READY, loading = false)
            return
        }
        if (settings.source == MostUsedSource.LOCAL) {
            mutableState.value = localState(base, apps, pins, history)
            return
        }
        if (!access) {
            mutableState.value = localState(base, apps, pins, history,
                "Usage Access is off. Showing launches learned on this device.")
            return
        }
        val usage = try {
            withContext(ioDispatcher) {
                usageSource.queryForegroundUsage(settings.days, apps.mapTo(HashSet()) { it.key.packageName })
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            mutableState.value = localState(base, apps, pins, history,
                "Android usage statistics are unavailable. Showing launches learned on this device.")
            return
        }
        // Permission may be revoked while Android's query is running. Discard those results.
        if (!accessOrFalse()) {
            mutableState.value = localState(base.copy(hasUsageAccess = false), apps, pins, history,
                "Usage Access was revoked. Showing launches learned on this device.")
            return
        }
        val scores = usage.millisByPackage
        val candidates = apps.filter { app ->
            app.key.profileSerial == usage.ownProfileSerial &&
                app.key.packageName != usageSource.corePackageName &&
                (scores[app.key.packageName] ?: 0L) > 0L
        }
        val suggestions = rank(candidates, settings.pinnedKeys.toSet(), settings.suggestedCount) {
            (scores[it.key.packageName] ?: 0L).toDouble()
        }
        mutableState.value = base.copy(
            featuredApps = pins + suggestions,
            featureState = if (candidates.isNotEmpty()) FeaturedAppsResultParcel.STATE_READY
                else FeaturedAppsResultParcel.STATE_NO_HISTORY,
            message = if (candidates.isNotEmpty()) null
                else "Android reported no foreground time for these apps in the selected period.",
            hasUsageAccess = true,
            loading = false,
        )
    }

    private fun localState(
        base: BuiltInMostUsedState,
        apps: List<LaunchableApp>,
        pins: List<LaunchableApp>,
        history: LocalMostUsedHistory,
        fallbackMessage: String? = null,
    ): BuiltInMostUsedState {
        val scores = history.scores(base.settings.days)
        val candidates = apps.filter {
            it.key.packageName != usageSource.corePackageName && (scores[it.key] ?: 0.0) > 0.0
        }
        val suggestions = rank(candidates, base.pinnedKeys.toSet(), base.settings.suggestedCount) {
            scores[it.key] ?: 0.0
        }
        return base.copy(
            featuredApps = pins + suggestions,
            featureState = if (candidates.isNotEmpty()) FeaturedAppsResultParcel.STATE_READY
                else FeaturedAppsResultParcel.STATE_NO_HISTORY,
            effectiveSource = MostUsedSource.LOCAL,
            usingLocalFallback = fallbackMessage != null,
            message = fallbackMessage ?: if (candidates.isEmpty())
                "Launch apps to teach Most Used which ones you use." else null,
            loading = false,
        )
    }

    private fun rank(
        apps: List<LaunchableApp>,
        pinned: Set<AppKey>,
        count: Int,
        score: (LaunchableApp) -> Double,
    ): List<LaunchableApp> {
        val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
        return apps.filter { it.key !in pinned && it.key.packageName != usageSource.corePackageName }
            .sortedWith { left, right ->
                compareValues(score(right), score(left)).takeIf { it != 0 }
                    ?: collator.compare(left.label, right.label).takeIf { it != 0 }
                    ?: left.label.compareTo(right.label).takeIf { it != 0 }
                    ?: left.key.packageName.compareTo(right.key.packageName).takeIf { it != 0 }
                    ?: left.key.activityName.compareTo(right.key.activityName).takeIf { it != 0 }
                    ?: left.key.profileSerial.compareTo(right.key.profileSerial)
            }.take(count)
    }

    private suspend fun accessOrFalse(): Boolean = try {
        withContext(ioDispatcher) { usageSource.hasUsageAccess() }
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        false
    }

    private fun usableApps(catalog: AppCatalog): List<LaunchableApp> = catalog.apps.filter { app ->
        app.enabled && catalog.profiles.none { profile ->
            profile.serialNumber == app.key.profileSerial && profile.quietModeEnabled
        }
    }

    private data class Snapshot(
        val catalog: AppCatalog,
        val settings: MostUsedSettings,
        val error: String?,
        val customizations: AppCustomizations,
        val history: LocalMostUsedHistory,
        val historyError: String?,
    )
}
