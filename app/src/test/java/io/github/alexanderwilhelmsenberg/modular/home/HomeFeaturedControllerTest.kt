package io.github.alexanderwilhelmsenberg.modular.home

import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.AppProfile
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityProviderKind
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityRoute
import io.github.alexanderwilhelmsenberg.modular.composition.ModuleInstance
import io.github.alexanderwilhelmsenberg.modular.composition.ResolvedContextBinding
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppKeyParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortItemParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedAppsRequestParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedAppsResultParcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HomeFeaturedControllerTest {
    private val available = app("available", 1)
    private val quiet = app("quiet", 2)
    private val missing = AppKey("example.missing", "MissingActivity", 1)
    private val catalog = AppCatalog(
        generation = 9,
        apps = listOf(available, quiet),
        profiles = listOf(AppProfile(1, false), AppProfile(2, true)),
    )
    private val request = FeaturedAppsRequestParcel(
        "read-9", 9, "en-US", catalog.apps.map { AppSortItemParcel(it.key.parcel(), it.label) },
    )
    private val route = CapabilityRoute(
        StandardCapabilities.AppsFeatured, CapabilityProviderKind.MODULE, "example.provider",
    )
    private val binding = ResolvedContextBinding(
        StandardCapabilities.AppsFeatured, ModuleInstance("one", "One", route.providerId), route,
    )

    @Test
    fun savedMissingAndQuietPinsRemainManagementOnly() {
        val state = HomeFeaturedController.validatedState(catalog, binding, request, result(
            pins = listOf(missing.parcel(), quiet.key.parcel(), available.key.parcel()),
        ))

        assertEquals(listOf(available), state.pinnedApps)
        assertEquals(listOf(missing, quiet.key), state.unavailablePinnedKeys)
        assertEquals(listOf(missing, quiet.key, available.key), state.pinnedKeys)
        assertEquals("Label available", state.featuredApps.single().label)
    }

    @Test
    fun unknownSuggestionCannotBecomeActionable() {
        assertThrows(IllegalArgumentException::class.java) {
            HomeFeaturedController.validatedState(catalog, binding, request, result(
                suggestions = listOf(missing.parcel()),
            ))
        }
    }

    @Test
    fun unavailableUsageCannotInventSuggestions() {
        assertThrows(IllegalArgumentException::class.java) {
            HomeFeaturedController.validatedState(catalog, binding, request, result(
                state = FeaturedAppsResultParcel.STATE_PERMISSION_REQUIRED,
                suggestions = listOf(available.key.parcel()),
            ))
        }
    }

    private fun result(
        pins: List<AppKeyParcel> = emptyList(),
        suggestions: List<AppKeyParcel> = emptyList(),
        state: Int = FeaturedAppsResultParcel.STATE_READY,
    ) = FeaturedAppsResultParcel(
        "read-9", 9, 4, FeaturedAppsResultParcel.STATUS_SUCCESS, state,
        pins, suggestions, null,
    )

    private fun app(name: String, profile: Long) = LaunchableApp(
        AppKey("example.$name", "${name}Activity", profile), "Label $name", 0, true, null,
    )

    private fun AppKey.parcel() = AppKeyParcel(packageName, activityName, profileSerial)
}
