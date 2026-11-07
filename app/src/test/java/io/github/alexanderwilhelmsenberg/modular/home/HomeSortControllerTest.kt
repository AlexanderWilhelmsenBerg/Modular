package io.github.alexanderwilhelmsenberg.modular.home

import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityProviderKind
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityRoute
import io.github.alexanderwilhelmsenberg.modular.composition.ActiveContextState
import io.github.alexanderwilhelmsenberg.modular.composition.ContextCapabilityBinding
import io.github.alexanderwilhelmsenberg.modular.composition.LauncherContext
import io.github.alexanderwilhelmsenberg.modular.composition.ModuleInstance
import io.github.alexanderwilhelmsenberg.modular.composition.ResolvedContextBinding
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeSortControllerTest {
    private val alpha = app("alpha", "Alpha")
    private val beta = app("beta", "Beta")
    private val source = listOf(beta, alpha)
    private val route = CapabilityRoute(
        StandardCapabilities.AppsSort,
        CapabilityProviderKind.MODULE,
        "sort.provider",
    )

    @Test
    fun selectedHelperChangesHomeOrderAndKeepsCoreObjects() = runTest {
        val catalog = MutableStateFlow(AppCatalog(apps = source))
        val active = MutableStateFlow(ActiveContextState.NONE)
        val routes = MutableStateFlow(listOf(route))
        var calls = 0
        val controller = HomeSortController(catalog, active, routes, { _, _, _, _ ->
            calls++
            Result.success(listOf(beta.copy(label = "Spoofed"), alpha))
        }, scope = this)

        runCurrent()
        assertEquals(listOf("Alpha", "Beta"), controller.orderedApps.value.map { it.label })
        assertFalse(controller.customOrderActive.value)
        assertEquals(0, calls)

        active.value = selected()
        runCurrent()
        assertEquals(listOf("Beta", "Alpha"), controller.orderedApps.value.map { it.label })
        assertTrue(controller.customOrderActive.value)
        assertSame(beta, controller.orderedApps.value[0])
        assertEquals(1, calls)
        controller.close()
    }

    @Test
    fun failureInvalidResultAndUnavailableRouteRestoreAlphabetical() = runTest {
        val catalog = MutableStateFlow(AppCatalog(apps = source))
        val active = MutableStateFlow(selected())
        val routes = MutableStateFlow(listOf(route))
        var response: Result<List<LaunchableApp>> = Result.success(listOf(beta, alpha))
        val failures = mutableListOf<String>()
        val controller = HomeSortController(catalog, active, routes, { _, _, _, _ -> response },
            onFailure = { _, message -> failures += message }, scope = this)
        runCurrent()
        assertEquals(listOf("Beta", "Alpha"), controller.orderedApps.value.map { it.label })
        assertTrue(controller.customOrderActive.value)

        response = Result.failure(IllegalStateException("provider died"))
        catalog.value = catalog.value.copy(generation = 1)
        runCurrent()
        assertEquals(listOf("Alpha", "Beta"), controller.orderedApps.value.map { it.label })
        assertFalse(controller.customOrderActive.value)

        response = Result.success(listOf(beta, beta))
        catalog.value = catalog.value.copy(generation = 2)
        runCurrent()
        assertEquals(listOf("Alpha", "Beta"), controller.orderedApps.value.map { it.label })
        assertFalse(controller.customOrderActive.value)
        assertEquals(1, failures.size)

        response = Result.success(listOf(beta, alpha))
        catalog.value = catalog.value.copy(generation = 3)
        runCurrent()
        routes.value = emptyList()
        runCurrent()
        assertEquals(listOf("Alpha", "Beta"), controller.orderedApps.value.map { it.label })
        assertFalse(controller.customOrderActive.value)
        assertEquals(1, failures.size)
        controller.close()
    }

    @Test
    fun timeoutAndLateResponseCannotReplaceNewCatalog() = runTest {
        val catalog = MutableStateFlow(AppCatalog(apps = source))
        val active = MutableStateFlow(selected())
        val routes = MutableStateFlow(listOf(route))
        val delayed = CompletableDeferred<Result<List<LaunchableApp>>>()
        val failures = mutableListOf<String>()
        val controller = HomeSortController(catalog, active, routes, { _, _, _, _ -> delayed.await() },
            timeoutMillis = 100, onFailure = { _, message -> failures += message }, scope = this)
        runCurrent()
        advanceTimeBy(101)
        runCurrent()
        assertEquals(listOf("Alpha", "Beta"), controller.orderedApps.value.map { it.label })
        assertFalse(controller.customOrderActive.value)
        assertEquals(listOf("Sort helper timed out. Retry its connection in Settings."), failures)

        catalog.value = AppCatalog(generation = 1, apps = listOf(app("gamma", "Gamma"), alpha))
        runCurrent()
        delayed.complete(Result.success(listOf(beta, alpha)))
        runCurrent()
        assertEquals(listOf("Alpha", "Gamma"), controller.orderedApps.value.map { it.label })
        controller.close()
    }

    @Test
    fun deactivationDiscardsOutstandingHelperResponse() = runTest {
        val catalog = MutableStateFlow(AppCatalog(apps = source))
        val active = MutableStateFlow(selected())
        val routes = MutableStateFlow(listOf(route))
        val delayed = CompletableDeferred<Result<List<LaunchableApp>>>()
        val controller = HomeSortController(catalog, active, routes, { _, _, _, _ -> delayed.await() }, scope = this)
        runCurrent()
        active.value = ActiveContextState.NONE
        runCurrent()
        delayed.complete(Result.success(listOf(beta, alpha)))
        runCurrent()
        assertEquals(listOf("Alpha", "Beta"), controller.orderedApps.value.map { it.label })
        assertFalse(controller.customOrderActive.value)
        controller.close()
    }

    @Test
    fun providerSwitchRejectsOldProvidersLateOrder() = runTest {
        val secondRoute = route.copy(providerId = "second.provider")
        val catalog = MutableStateFlow(AppCatalog(apps = source))
        val active = MutableStateFlow(selected())
        val routes = MutableStateFlow(listOf(route, secondRoute))
        val oldResponse = CompletableDeferred<Result<List<LaunchableApp>>>()
        val controller = HomeSortController(catalog, active, routes, { selectedRoute, _, _, _ ->
            if (selectedRoute == route) oldResponse.await()
            else Result.success(listOf(beta, alpha))
        }, scope = this)
        runCurrent()

        active.value = selected(secondRoute)
        runCurrent()
        assertEquals(listOf("Beta", "Alpha"), controller.orderedApps.value.map { it.label })
        assertTrue(controller.customOrderActive.value)

        oldResponse.complete(Result.success(listOf(alpha, beta)))
        runCurrent()
        assertEquals(listOf("Beta", "Alpha"), controller.orderedApps.value.map { it.label })
        assertTrue(controller.customOrderActive.value)
        controller.close()
    }

    @Test
    fun instanceConfigurationChangePassesNewBindingAndDiscardsOldOrder() = runTest {
        val catalog = MutableStateFlow(AppCatalog(apps = source))
        val active = MutableStateFlow(selected())
        val routes = MutableStateFlow(listOf(route))
        val oldResponse = CompletableDeferred<Result<List<LaunchableApp>>>()
        val seen = mutableListOf<Pair<String, String>>()
        val controller = HomeSortController(catalog, active, routes, { _, binding, _, _ ->
            seen += binding.instance.id to binding.instance.configurationJson
            if (binding.instance.configurationJson == "{}") oldResponse.await()
            else Result.success(listOf(beta, alpha))
        }, scope = this)
        runCurrent()

        active.value = selected(configurationJson = "{\"order\":\"reverse\"}")
        runCurrent()
        assertEquals(listOf("Beta", "Alpha"), controller.orderedApps.value.map { it.label })
        assertTrue(controller.customOrderActive.value)
        assertEquals(listOf("instance" to "{}", "instance" to "{\"order\":\"reverse\"}"), seen)

        oldResponse.complete(Result.success(listOf(alpha, beta)))
        runCurrent()
        assertEquals(listOf("Beta", "Alpha"), controller.orderedApps.value.map { it.label })
        assertTrue(controller.customOrderActive.value)
        controller.close()
    }

    @Test
    fun reconnectDiscardsOldSessionsLateOrder() = runTest {
        val oldRoute = route.copy(sessionId = "old-session")
        val newRoute = route.copy(sessionId = "new-session")
        val catalog = MutableStateFlow(AppCatalog(apps = source))
        val active = MutableStateFlow(selected(oldRoute))
        val routes = MutableStateFlow(listOf(oldRoute))
        val oldResponse = CompletableDeferred<Result<List<LaunchableApp>>>()
        val controller = HomeSortController(catalog, active, routes, { selectedRoute, _, _, _ ->
            if (selectedRoute == oldRoute) oldResponse.await()
            else Result.success(listOf(beta, alpha))
        }, scope = this)
        runCurrent()

        routes.value = listOf(newRoute)
        active.value = selected(newRoute)
        runCurrent()
        assertEquals(listOf("Beta", "Alpha"), controller.orderedApps.value.map { it.label })
        assertTrue(controller.customOrderActive.value)

        oldResponse.complete(Result.success(listOf(alpha, beta)))
        runCurrent()
        assertEquals(listOf("Beta", "Alpha"), controller.orderedApps.value.map { it.label })
        assertTrue(controller.customOrderActive.value)
        controller.close()
    }

    @Test
    fun providerFailureDoesNotEchoCatalogDetailsInDiagnostic() = runTest {
        val sentinel = "private-app-catalog-sentinel"
        val catalog = MutableStateFlow(AppCatalog(apps = source))
        val active = MutableStateFlow(selected())
        val routes = MutableStateFlow(listOf(route))
        val failures = mutableListOf<String>()
        val controller = HomeSortController(
            catalog, active, routes,
            { _, _, _, _ -> Result.failure(IllegalStateException("Provider leaked $sentinel")) },
            onFailure = { _, message -> failures += message },
            scope = this,
        )

        runCurrent()
        assertEquals(
            listOf("Sort helper failed (unavailable). Retry its connection in Settings."),
            failures,
        )
        assertFalse(failures.single().contains(sentinel))
        controller.close()
    }

    private fun selected(selectedRoute: CapabilityRoute = route, configurationJson: String = "{}"): ActiveContextState {
        val instance = ModuleInstance("instance", "Sort", selectedRoute.providerId, configurationJson)
        val context = LauncherContext("context", "Home", listOf(
            ContextCapabilityBinding(StandardCapabilities.AppsSort, instance.id),
        ))
        return ActiveContextState(context, listOf(
            ResolvedContextBinding(StandardCapabilities.AppsSort, instance, selectedRoute),
        ))
    }

    private fun app(name: String, label: String) = LaunchableApp(
        AppKey("$name.pkg", "$name.pkg.Main", 1), label, 0, true, null,
    )
}
