package io.github.alexanderwilhelmsenberg.modular.mostused

import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityProviderKind
import io.github.alexanderwilhelmsenberg.modular.composition.ModuleSettingsController
import io.github.alexanderwilhelmsenberg.modular.home.HomeFeaturedState
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/** Moves the selected official add-on's public featured result into Core before detaching it. */
class LegacyMostUsedMigration(
    private val moduleSettings: ModuleSettingsController,
    private val coreSettings: MostUsedSettingsStore,
    private val corePackageName: String,
) {
    suspend fun migrate(featured: HomeFeaturedState): Result<Boolean> {
        return try {
        if (!isLegacy(featured, corePackageName)) return Result.success(false)
        val binding = featured.binding
        val expectedContext = moduleSettings.selectedHomeContextFor(binding)
            ?: return Result.success(false)
        val sourceId = "${binding.route.providerId}:${binding.route.component!!.packageName}:${binding.instance.id}"
        val config = Json.parseToJsonElement(binding.instance.configurationJson) as JsonObject
        val days = config["days"]?.jsonPrimitive?.int ?: 7
        val suggestedCount = config["suggestedCount"]?.jsonPrimitive?.int ?: 8
        saveBeforeDetach(
            save = { coreSettings.importLegacy(sourceId, featured.pinnedKeys, days, suggestedCount) },
            detach = { moduleSettings.detachSelectedHomeBinding(binding, expectedContext) },
        )
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        Result.failure(failure)
        }
    }

    companion object {
        private const val LEGACY_PROVIDER_ID = "modular.addon.sort.mostused"
        private const val LEGACY_PACKAGE =
            "io.github.alexanderwilhelmsenberg.modular.addon.sort.mostused"

        /** A selected and validated official provider from the matching build variant. */
        fun isLegacy(featured: HomeFeaturedState, corePackageName: String): Boolean {
            val binding = featured.binding
            val expectedPackage = LEGACY_PACKAGE +
                if (corePackageName.endsWith(".debug")) ".debug" else ""
            return binding.capability == StandardCapabilities.AppsFeatured &&
                binding.route.capability == StandardCapabilities.AppsFeatured &&
                binding.route.providerKind == CapabilityProviderKind.MODULE &&
                binding.route.providerId == LEGACY_PROVIDER_ID &&
                binding.instance.providerId == LEGACY_PROVIDER_ID &&
                binding.route.component?.packageName == expectedPackage
        }
    }
}

/** The DataStore commit must finish successfully before changing the selected Home context. */
internal suspend fun saveBeforeDetach(
    save: suspend () -> Result<Unit>,
    detach: suspend () -> Result<Boolean>,
): Result<Boolean> {
    save().getOrThrow()
    return detach()
}
