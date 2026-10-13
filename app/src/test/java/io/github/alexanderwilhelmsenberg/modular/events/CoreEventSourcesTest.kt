package io.github.alexanderwilhelmsenberg.modular.events

import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.composition.ActiveContextState
import io.github.alexanderwilhelmsenberg.modular.composition.CompositionState
import io.github.alexanderwilhelmsenberg.modular.composition.LauncherContext
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleRecord
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class CoreEventSourcesTest {
    @Test
    fun bridgesCompletedStateChangesButNotInitialBaseline() =
        runTest {
            val dispatcher =
                StandardTestDispatcher(testScheduler)
            val modules =
                MutableStateFlow(emptyList<ModuleRecord>())
            val catalog = MutableStateFlow(
                AppCatalog(
                    generation = 1,
                    apps = listOf(
                        app("one.pkg", "One"),
                    ),
                ),
            )
            val composition =
                MutableStateFlow(CompositionState())
            val active =
                MutableStateFlow(ActiveContextState.NONE)

            val bus = CoreEventBus(
                dispatcher = dispatcher,
            )
            val sources = CoreEventSources(
                eventBus = bus,
                modules = modules,
                appCatalog = catalog,
                composition = composition,
                activeContext = active,
                dispatcher = dispatcher,
            )

            try {
                runCurrent()

                val next = async(
                    start = CoroutineStart.UNDISPATCHED,
                ) {
                    withTimeout(1_000) {
                        bus.events.first()
                    }
                }

                catalog.value = AppCatalog(
                    generation = 2,
                    apps = listOf(
                        app("one.pkg", "One"),
                        app("two.pkg", "Two"),
                    ),
                )

                runCurrent()

                val envelope = next.await()
                val event =
                    envelope.event as CoreEvent.AppsChanged

                assertEquals(1L, envelope.sequence)
                assertEquals(
                    listOf(key("two.pkg")),
                    event.added,
                )
            } finally {
                sources.close()
                bus.close()
            }
        }

    private fun app(
        packageName: String,
        label: String,
    ) = LaunchableApp(
        key = key(packageName),
        label = label,
        firstInstallTime = 1,
        enabled = true,
        loadingProgress = null,
    )

    private fun key(
        packageName: String,
    ) = AppKey(
        packageName = packageName,
        activityName =
            packageName + ".MainActivity",
        profileSerial = 1,
    )
}
