package io.github.alexanderwilhelmsenberg.modular.mostused

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.apps.AppCustomizations
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.AppRepository
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedAppsResultParcel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BuiltInMostUsedControllerTest {
    private val first = AppKey("one.app", "Main", 1)
    private val second = AppKey("two.app", "Main", 1)
    private val work = AppKey("work.app", "Main", 2)
    private val missing = AppKey("gone.app", "Main", 1)

    @Test fun disabledNeverQueriesHistoryAndPinsSurviveRevocation() = runTest {
        val store = MostUsedSettingsStore(MemoryPreferences())
        store.setSource(MostUsedSource.ANDROID)
        val repository = FakeRepository(listOf(app(first), app(second)))
        val usage = FakeUsage()
        val controller = BuiltInMostUsedController(repository, store, usage, controllerScope(),
            StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertEquals(0, usage.queries)
        assertFalse(controller.state.value.settings.enabled)

        assertTrue(controller.setPinned(first, true).isSuccess)
        assertTrue(store.setEnabled(true).isSuccess)
        advanceUntilIdle()
        assertEquals(1, usage.queries)
        assertEquals(listOf(first, second), controller.state.value.featuredApps.map { it.key })

        usage.allowed = false
        controller.refresh()
        advanceUntilIdle()
        assertEquals(1, usage.queries)
        assertEquals(listOf(first), controller.state.value.featuredApps.map { it.key })
        assertEquals(FeaturedAppsResultParcel.STATE_NO_HISTORY, controller.state.value.featureState)
        assertFalse(controller.state.value.hasUsageAccess)
        assertTrue(controller.state.value.usingLocalFallback)
    }

    @Test fun suggestionsUseOwnProfileAndExcludeCoreAndZeroTime() = runTest {
        val core = AppKey("core.pkg", "Main", 1)
        val zero = AppKey("zero.app", "Main", 1)
        val store = MostUsedSettingsStore(MemoryPreferences())
        store.setEnabled(true)
        store.setSource(MostUsedSource.ANDROID)
        val repository = FakeRepository(listOf(app(first), app(second), app(work), app(core), app(zero)))
        val usage = FakeUsage(weights = mapOf(
            first.packageName to 10L, second.packageName to 100L,
            work.packageName to 1_000L, core.packageName to 10_000L,
        ))
        val controller = BuiltInMostUsedController(repository, store, usage, controllerScope(),
            StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertEquals(listOf(second, first), controller.state.value.featuredApps.map { it.key })
        assertEquals(FeaturedAppsResultParcel.STATE_READY, controller.state.value.featureState)
        assertTrue(controller.state.value.hasUsageAccess)
    }

    @Test fun hiddenHighUsageDoesNotConsumeLimitAndRenamedLabelsBreakTies() = runTest {
        val third = AppKey("three.app", "Main", 1)
        val store = MostUsedSettingsStore(MemoryPreferences())
        store.setEnabled(true)
        store.setSuggestedCount(2)
        store.setSource(MostUsedSource.ANDROID)
        val customizations = MutableStateFlow(AppCustomizations(
            labels = mapOf(first.stableId to "Zed", second.stableId to "Aaa"),
            hidden = setOf(third.stableId),
        ))
        val usage = FakeUsage(weights = mapOf(
            first.packageName to 10L, second.packageName to 10L, third.packageName to 1_000L,
        ))
        val controller = BuiltInMostUsedController(
            FakeRepository(listOf(app(first), app(second), app(third))), store, usage,
            controllerScope(), StandardTestDispatcher(testScheduler), customizations,
        )
        advanceUntilIdle()
        assertEquals(listOf(second, first), controller.state.value.featuredApps.map { it.key })
        assertEquals(listOf("Aaa", "Zed"), controller.state.value.featuredApps.map { it.label })
    }

    @Test fun unavailablePinsRemainOrderedAndCannotBeNewlyAdded() = runTest {
        val store = MostUsedSettingsStore(MemoryPreferences())
        store.importLegacy("old-instance", listOf(missing, first), 7, 8)
        store.setEnabled(true).getOrThrow()
        val repository = FakeRepository(listOf(app(first)))
        val controller = BuiltInMostUsedController(repository, store, FakeUsage(allowed = false), controllerScope(),
            StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertEquals(listOf(missing, first), controller.state.value.pinnedKeys)
        assertEquals(listOf(missing), controller.state.value.unavailablePinnedKeys)
        assertEquals(listOf(first), controller.state.value.featuredApps.map { it.key })
        assertTrue(controller.setPinned(missing, false).isSuccess)
        assertTrue(controller.setPinned(missing, true).isFailure)
    }

    @Test fun revocationDuringQueryDiscardsSuggestions() = runTest {
        val store = MostUsedSettingsStore(MemoryPreferences())
        store.setEnabled(true)
        store.setSource(MostUsedSource.ANDROID)
        val usage = FakeUsage(revokeOnQuery = true)
        val controller = BuiltInMostUsedController(FakeRepository(listOf(app(first))), store, usage,
            controllerScope(), StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertEquals(1, usage.queries)
        assertTrue(controller.state.value.featuredApps.isEmpty())
        assertEquals(FeaturedAppsResultParcel.STATE_NO_HISTORY, controller.state.value.featureState)
        assertTrue(controller.state.value.usingLocalFallback)
    }

    @Test fun zeroSuggestionsSkipsUsageQueryWithoutAccess() = runTest {
        val store = MostUsedSettingsStore(MemoryPreferences())
        store.setEnabled(true)
        store.setSuggestedCount(0)
        store.setSource(MostUsedSource.ANDROID)
        val usage = FakeUsage()
        val controller = BuiltInMostUsedController(FakeRepository(listOf(app(first))), store, usage,
            controllerScope(), StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertEquals(0, usage.queries)
        assertEquals(FeaturedAppsResultParcel.STATE_READY, controller.state.value.featureState)
        assertTrue(controller.state.value.hasUsageAccess)
        assertEquals(null, controller.state.value.message)
        usage.allowed = false
        controller.refresh()
        advanceUntilIdle()
        assertEquals(0, usage.queries)
        assertEquals(FeaturedAppsResultParcel.STATE_READY, controller.state.value.featureState)
        assertFalse(controller.state.value.hasUsageAccess)
        assertEquals(null, controller.state.value.message)
        usage.throwOnAccess = true
        controller.refresh()
        advanceUntilIdle()
        assertEquals(0, usage.queries)
        assertEquals(FeaturedAppsResultParcel.STATE_READY, controller.state.value.featureState)
        assertFalse(controller.state.value.hasUsageAccess)
        assertEquals(null, controller.state.value.message)
    }

    @Test fun storeRejectsInvalidMutationsAndMigratesAtomically() = runTest {
        val store = MostUsedSettingsStore(MemoryPreferences())
        assertEquals(MostUsedSettings(), store.currentSettings())
        assertEquals(MostUsedSource.LOCAL, store.currentSettings().source)
        assertTrue(store.setDays(31).isFailure)
        assertTrue(store.setSuggestedCount(-1).isFailure)
        assertTrue(store.setPinned(first, true, emptySet()).isFailure)
        assertTrue(store.setPinned(first, true, setOf(first)).isSuccess)
        assertTrue(store.reorderPins(listOf(second)).isFailure)
        assertTrue(store.setDays(4).isSuccess)
        assertTrue(store.importLegacy("legacy", listOf(second, first), 14, 12).isSuccess)
        assertEquals(listOf(first, second), store.currentSettings().pinnedKeys)
        assertEquals(4, store.currentSettings().days)
        assertEquals(12, store.currentSettings().suggestedCount)
        assertFalse(store.currentSettings().enabled)
        assertTrue(store.setPinned(second, false, emptySet()).isSuccess)
        assertTrue(store.importLegacy("legacy", listOf(second), 20, 3).isSuccess)
        assertEquals(listOf(first), store.currentSettings().pinnedKeys)
        assertEquals(setOf("legacy"), store.currentSettings().importedLegacySources)
        val tooMany = (1..50).map { AppKey("package$it", "Main", 1) }
        assertTrue(store.importLegacy("overflow", tooMany, 7, 8).isFailure)
        assertEquals(listOf(first), store.currentSettings().pinnedKeys)
        assertEquals(setOf("legacy"), store.currentSettings().importedLegacySources)
    }

    @Test fun corruptSettingsDoNotGetOverwrittenByMutation() = runTest {
        val key = stringPreferencesKey("settings_json_v1")
        val initial = emptyPreferences().toMutablePreferences().apply { this[key] = "{broken" }
        val memory = MemoryPreferences(initial)
        val store = MostUsedSettingsStore(memory)
        assertEquals(MostUsedSettings(), store.currentSettings())
        assertEquals("Could not load Most Used settings.", store.error.value)
        assertTrue(store.setEnabled(true).isFailure)
        assertEquals("{broken", memory.data.value[key])
    }

    @Test fun olderSettingsKeepPinsAndTogglesButDefaultToLocal() = runTest {
        val key = stringPreferencesKey("settings_json_v1")
        val old = """{"enabled":true,"days":14,"suggestedCount":3,"pins":[{"packageName":"one.app","activityName":"Main","profileSerial":1}],"enabledTouched":true,"daysTouched":true,"countTouched":true,"pinsTouched":true}"""
        val memory = MemoryPreferences(emptyPreferences().toMutablePreferences().apply { this[key] = old })
        val settings = MostUsedSettingsStore(memory).currentSettings()
        assertTrue(settings.enabled)
        assertEquals(14, settings.days)
        assertEquals(3, settings.suggestedCount)
        assertEquals(listOf(first), settings.pinnedKeys)
        assertEquals(MostUsedSource.LOCAL, settings.source)
    }

    @Test fun localDefaultLearnsOnlySuccessfulEnabledHomeLaunchesWithoutUsageAccessChecks() = runTest {
        val store = MostUsedSettingsStore(MemoryPreferences())
        val history = LocalMostUsedHistoryStore(MemoryPreferences(), { 0L })
        val repository = FakeRepository(listOf(app(first), app(second)))
        val usage = FakeUsage(allowed = false).apply { throwOnAccess = true }
        val controller = BuiltInMostUsedController(repository, store, usage, controllerScope(),
            StandardTestDispatcher(testScheduler), localHistory = history)
        advanceUntilIdle()
        assertTrue(controller.launchFromHome(first).isSuccess)
        assertTrue(history.currentHistory().scores(7).isEmpty())
        store.setEnabled(true).getOrThrow()
        repository.failedKeys += second
        assertTrue(controller.launchFromHome(second).isFailure)
        assertTrue(history.currentHistory().scores(7).isEmpty())
        assertTrue(controller.launchFromHome(first).isSuccess)
        advanceUntilIdle()
        assertEquals(listOf(first), controller.state.value.featuredApps.map { it.key })
        assertEquals(MostUsedSource.LOCAL, controller.state.value.effectiveSource)
        assertEquals(0, usage.queries)
        assertEquals(0, usage.accessChecks)
    }

    @Test fun deniedAndroidHistoryUsesLocalSuggestionsAndResetKeepsPins() = runTest {
        val store = MostUsedSettingsStore(MemoryPreferences())
        store.setEnabled(true).getOrThrow()
        store.setSource(MostUsedSource.ANDROID).getOrThrow()
        store.setPinned(second, true, setOf(first, second)).getOrThrow()
        val history = LocalMostUsedHistoryStore(MemoryPreferences(), { 0L })
        history.recordLaunch(first).getOrThrow()
        val usage = FakeUsage(allowed = false)
        val controller = BuiltInMostUsedController(FakeRepository(listOf(app(first), app(second))),
            store, usage, controllerScope(), StandardTestDispatcher(testScheduler), localHistory = history)
        advanceUntilIdle()
        assertEquals(listOf(second, first), controller.state.value.featuredApps.map { it.key })
        assertEquals(MostUsedSource.ANDROID, controller.state.value.settings.source)
        assertEquals(MostUsedSource.LOCAL, controller.state.value.effectiveSource)
        assertTrue(controller.state.value.usingLocalFallback)
        assertTrue(controller.state.value.message!!.contains("Usage Access"))
        assertEquals(0, usage.queries)
        controller.resetLearnedUsage().getOrThrow()
        advanceUntilIdle()
        assertEquals(listOf(second), controller.state.value.featuredApps.map { it.key })
        assertEquals(listOf(second), store.currentSettings().pinnedKeys)
    }

    @Test fun localHistoryFiltersHiddenAppsBeforeLimitAndUsesLabelsForTies() = runTest {
        val third = AppKey("three.app", "Main", 1)
        val store = MostUsedSettingsStore(MemoryPreferences())
        store.setEnabled(true).getOrThrow()
        store.setSuggestedCount(2).getOrThrow()
        val history = LocalMostUsedHistoryStore(MemoryPreferences(), { 0L })
        history.recordLaunch(first).getOrThrow()
        history.recordLaunch(second).getOrThrow()
        repeat(10) { history.recordLaunch(third).getOrThrow() }
        val customizations = MutableStateFlow(AppCustomizations(
            labels = mapOf(first.stableId to "Zed", second.stableId to "Aaa"),
            hidden = setOf(third.stableId),
        ))
        val controller = BuiltInMostUsedController(
            FakeRepository(listOf(app(first), app(second), app(third))), store,
            FakeUsage(allowed = false), controllerScope(), StandardTestDispatcher(testScheduler),
            customizations, history,
        )
        advanceUntilIdle()
        assertEquals(listOf(second, first), controller.state.value.featuredApps.map { it.key })
    }

    @Test fun successfulLaunchRemainsSuccessfulWhenHistoryWriteFails() = runTest {
        val store = MostUsedSettingsStore(MemoryPreferences())
        store.setEnabled(true).getOrThrow()
        val data = MemoryPreferences().apply { failWrites = true }
        val controller = BuiltInMostUsedController(FakeRepository(listOf(app(first))), store,
            FakeUsage(), controllerScope(), StandardTestDispatcher(testScheduler),
            localHistory = LocalMostUsedHistoryStore(data, { 0L }))
        advanceUntilIdle()
        assertTrue(controller.launchFromHome(first).isSuccess)
        advanceUntilIdle()
        assertEquals("Could not save learned app usage.", controller.state.value.historyError)
    }

    private fun app(key: AppKey) = LaunchableApp(key, key.packageName, 0L, true, null)

    private fun TestScope.controllerScope() = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

    private class FakeRepository(apps: List<LaunchableApp>) : AppRepository {
        val failedKeys = HashSet<AppKey>()
        override val catalog: StateFlow<AppCatalog> = MutableStateFlow(AppCatalog(apps = apps))
        override fun refresh() = Unit
        override suspend fun launch(key: AppKey): Result<Unit> =
            if (key in failedKeys) Result.failure(IllegalStateException("Launch failed")) else Result.success(Unit)
        override fun close() = Unit
    }

    private class FakeUsage(
        var allowed: Boolean = true,
        val weights: Map<String, Long> = mapOf("one.app" to 10L, "two.app" to 20L),
        val revokeOnQuery: Boolean = false,
    ) : MostUsedUsageSource {
        override val corePackageName = "core.pkg"
        var queries = 0
        var accessChecks = 0
        var throwOnAccess = false
        override fun hasUsageAccess(): Boolean {
            accessChecks++
            if (throwOnAccess) error("AppOps unavailable")
            return allowed
        }
        override fun queryForegroundUsage(days: Int, packages: Set<String>): ForegroundUsage {
            queries++
            if (revokeOnQuery) allowed = false
            return ForegroundUsage(1, weights)
        }
    }

    private class MemoryPreferences(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        var failWrites = false
        private val mutable = MutableStateFlow(initial)
        override val data: StateFlow<Preferences> = mutable
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (failWrites) error("DataStore write failed")
            val next = transform(mutable.value.toMutablePreferences())
            mutable.value = next
            return next
        }
    }
}
