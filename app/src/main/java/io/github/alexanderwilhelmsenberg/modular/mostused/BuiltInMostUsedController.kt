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
)

/** Native featured-app engine. It reads raw catalog entries and never binds to an add-on. */
class BuiltInMostUsedController(
    private val repository: AppRepository,
    private val store: MostUsedSettingsStore,
    private val usageSource: MostUsedUsageSource,
    scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val displayCustomizations: Flow<AppCustomizations> = flowOf(AppCustomizations()),
) {
    private val refreshSignal = MutableStateFlow(0L)
    private val mutableState = MutableStateFlow(BuiltInMostUsedState())
    val state: StateFlow<BuiltInMostUsedState> = mutableState.asStateFlow()

    init {
        scope.launch {
            try {
                combine(repository.catalog, store.settings, store.error, refreshSignal,
                    displayCustomizations) { catalog, settings, error, _, customizations ->
                    Snapshot(catalog, settings, error, customizations)
                }.collectLatest { snapshot ->
                    calculate(snapshot.catalog, snapshot.settings, snapshot.error, snapshot.customizations)
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

    suspend fun setPinned(key: AppKey, pinned: Boolean): Result<Unit> =
        store.setPinned(key, pinned, usableApps(repository.catalog.value).mapTo(HashSet()) { it.key })

    suspend fun reorderPins(keys: List<AppKey>): Result<Unit> = store.reorderPins(keys)

    private suspend fun calculate(
        catalog: AppCatalog,
        settings: MostUsedSettings,
        settingsError: String?,
        customizations: AppCustomizations,
    ) {
        val apps = customizations.visibleApps(usableApps(catalog))
        val available = apps.associateBy { it.key }
        val pins = settings.pinnedKeys.mapNotNull(available::get)
        val unavailable = settings.pinnedKeys.filterNot(available::containsKey)
        val base = BuiltInMostUsedState(
            settings = settings,
            pinnedKeys = settings.pinnedKeys,
            unavailablePinnedKeys = unavailable,
            featuredApps = if (settings.enabled) pins else emptyList(),
            loading = settings.enabled,
        )
        mutableState.value = base
        try {
            val access = accessOrFalse()
            if (settingsError != null) {
                mutableState.value = base.copy(hasUsageAccess = access, message = settingsError, loading = false)
                return
            }
            if (!settings.enabled) {
                mutableState.value = base.copy(hasUsageAccess = access, loading = false)
                return
            }
            if (settings.suggestedCount == 0) {
                mutableState.value = base.copy(
                    featureState = FeaturedAppsResultParcel.STATE_READY,
                    hasUsageAccess = access,
                    loading = false,
                )
                return
            }
            if (!access) {
                mutableState.value = base.copy(
                    featureState = FeaturedAppsResultParcel.STATE_PERMISSION_REQUIRED,
                    message = "Usage Access is off. Pin apps or enable access in Most Used settings.",
                    hasUsageAccess = false,
                    loading = false,
                )
                return
            }
            val usage = withContext(ioDispatcher) {
                usageSource.queryForegroundUsage(settings.days, apps.mapTo(HashSet()) { it.key.packageName })
            }
            // Permission may be revoked while Android's query is running. Discard those results.
            if (!accessOrFalse()) {
                mutableState.value = base.copy(
                    featureState = FeaturedAppsResultParcel.STATE_PERMISSION_REQUIRED,
                    message = "Usage Access is off. Pin apps or enable access in Most Used settings.",
                    hasUsageAccess = false,
                    loading = false,
                )
                return
            }
            val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
            val pinnedSet = settings.pinnedKeys.toSet()
            val candidates = apps.filter { app ->
                app.key !in pinnedSet && app.key.profileSerial == usage.ownProfileSerial &&
                    app.key.packageName != usageSource.corePackageName &&
                    (usage.millisByPackage[app.key.packageName] ?: 0L) > 0L
            }
            val suggestions = candidates.sortedWith { left, right ->
                compareValues(usage.millisByPackage[right.key.packageName] ?: 0L,
                    usage.millisByPackage[left.key.packageName] ?: 0L).takeIf { it != 0 }
                    ?: collator.compare(left.label, right.label).takeIf { it != 0 }
                    ?: left.label.compareTo(right.label).takeIf { it != 0 }
                    ?: left.key.packageName.compareTo(right.key.packageName).takeIf { it != 0 }
                    ?: left.key.activityName.compareTo(right.key.activityName).takeIf { it != 0 }
                    ?: left.key.profileSerial.compareTo(right.key.profileSerial)
            }.take(settings.suggestedCount)
            val hasHistory = apps.any { app ->
                app.key.profileSerial == usage.ownProfileSerial &&
                    app.key.packageName != usageSource.corePackageName &&
                    (usage.millisByPackage[app.key.packageName] ?: 0L) > 0L
            }
            mutableState.value = base.copy(
                featuredApps = pins + suggestions,
                featureState = if (hasHistory) FeaturedAppsResultParcel.STATE_READY
                    else FeaturedAppsResultParcel.STATE_NO_HISTORY,
                message = if (hasHistory) null
                    else "Android reported no foreground time for these apps in the selected period.",
                hasUsageAccess = true,
                loading = false,
            )
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            val access = accessOrFalse()
            mutableState.value = base.copy(
                featureState = if (access) FeaturedAppsResultParcel.STATE_NO_HISTORY
                    else FeaturedAppsResultParcel.STATE_PERMISSION_REQUIRED,
                message = if (access) "Usage statistics are unavailable."
                    else "Usage Access is off. Pin apps or enable access in Most Used settings.",
                hasUsageAccess = access,
                loading = false,
            )
        }
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
    )
}
