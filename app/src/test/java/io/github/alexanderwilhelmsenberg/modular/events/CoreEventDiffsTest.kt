package io.github.alexanderwilhelmsenberg.modular.events

import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.AppProfile
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.composition.ActiveContextState
import io.github.alexanderwilhelmsenberg.modular.composition.CompositionState
import io.github.alexanderwilhelmsenberg.modular.composition.ContextCapabilityBinding
import io.github.alexanderwilhelmsenberg.modular.composition.ContextResolutionProblem
import io.github.alexanderwilhelmsenberg.modular.composition.ContextResolutionProblemKind
import io.github.alexanderwilhelmsenberg.modular.composition.LauncherContext
import io.github.alexanderwilhelmsenberg.modular.composition.ModuleInstance
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreEventDiffsTest {
    @Test
    fun appDeltaContainsAddedRemovedChangedAndProfiles() {
        val previous = AppCatalog(
            generation = 1,
            profiles = listOf(
                AppProfile(
                    serialNumber = 10,
                    quietModeEnabled = false,
                ),
                AppProfile(
                    serialNumber = 20,
                    quietModeEnabled = false,
                ),
            ),
            apps = listOf(
                app("keep.pkg", "Old label"),
                app("remove.pkg", "Remove"),
            ),
        )
        val current = AppCatalog(
            generation = 2,
            profiles = listOf(
                AppProfile(
                    serialNumber = 10,
                    quietModeEnabled = true,
                ),
                AppProfile(
                    serialNumber = 30,
                    quietModeEnabled = false,
                ),
            ),
            apps = listOf(
                app("keep.pkg", "New label"),
                app("add.pkg", "Add"),
            ),
        )

        val events = CoreEventDiffs.apps(
            previous = previous,
            current = current,
        )

        val apps = events
            .filterIsInstance<CoreEvent.AppsChanged>()
            .single()
        assertEquals(
            listOf(key("add.pkg")),
            apps.added,
        )
        assertEquals(
            listOf(key("remove.pkg")),
            apps.removed,
        )
        assertEquals(
            listOf(key("keep.pkg")),
            apps.changed,
        )
        assertEquals(2L, apps.catalogGeneration)

        val profiles = events
            .filterIsInstance<CoreEvent.ProfilesChanged>()
            .single()
        assertEquals(listOf(30L), profiles.addedSerials)
        assertEquals(listOf(20L), profiles.removedSerials)
        assertEquals(listOf(10L), profiles.changedSerials)
    }

    @Test
    fun compositionDeltaUsesStableIdsOnly() {
        val previous = CompositionState(
            instances = listOf(
                ModuleInstance(
                    id = "instance-a",
                    name = "A",
                    providerId = "provider.a",
                ),
                ModuleInstance(
                    id = "instance-remove",
                    name = "Remove",
                    providerId = "provider.remove",
                ),
            ),
            contexts = listOf(
                LauncherContext(
                    id = "context-a",
                    name = "A",
                ),
            ),
        )
        val current = CompositionState(
            instances = listOf(
                ModuleInstance(
                    id = "instance-a",
                    name = "A renamed",
                    providerId = "provider.a",
                ),
                ModuleInstance(
                    id = "instance-add",
                    name = "Add",
                    providerId = "provider.add",
                ),
            ),
            contexts = listOf(
                LauncherContext(
                    id = "context-a",
                    name = "A renamed",
                ),
                LauncherContext(
                    id = "context-add",
                    name = "Add",
                ),
            ),
        )

        val event = requireNotNull(
            CoreEventDiffs.composition(
                previous = previous,
                current = current,
            ),
        )

        assertEquals(
            listOf("instance-add"),
            event.addedInstanceIds,
        )
        assertEquals(
            listOf("instance-remove"),
            event.removedInstanceIds,
        )
        assertEquals(
            listOf("instance-a"),
            event.changedInstanceIds,
        )
        assertEquals(
            listOf("context-add"),
            event.addedContextIds,
        )
        assertEquals(
            listOf("context-a"),
            event.changedContextIds,
        )
    }

    @Test
    fun activeContextEmitsSelectionAndHealthChanges() {
        val context = LauncherContext(
            id = "work",
            name = "Work",
            bindings = listOf(
                ContextCapabilityBinding(
                    capability =
                        StandardCapabilities.AppsSort,
                    instanceId = "sort-work",
                ),
            ),
        )

        val selected = ActiveContextState(
            context = context,
        )
        val degraded = ActiveContextState(
            context = context,
            problems = listOf(
                ContextResolutionProblem(
                    capability =
                        StandardCapabilities.AppsSort,
                    instanceId = "sort-work",
                    kind =
                        ContextResolutionProblemKind
                            .PROVIDER_UNAVAILABLE,
                    detail = "missing",
                ),
            ),
        )

        val selectionEvents =
            CoreEventDiffs.activeContext(
                previous = ActiveContextState.NONE,
                current = selected,
            )

        assertTrue(
            selectionEvents.any {
                it is CoreEvent.ActiveContextChanged &&
                    it.currentContextId == "work"
            },
        )

        val healthEvents =
            CoreEventDiffs.activeContext(
                previous = selected,
                current = degraded,
            )

        val health = healthEvents
            .filterIsInstance<
                CoreEvent.ActiveContextHealthChanged
            >()
            .single()

        assertTrue(!health.ready)
        assertEquals(
            ContextResolutionProblemKind.PROVIDER_UNAVAILABLE,
            health.problems.single().kind,
        )
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
