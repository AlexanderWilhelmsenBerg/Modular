package io.github.alexanderwilhelmsenberg.modular.workspace

import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.apps.AppCustomizations
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.organization.HeadsetKind

enum class ListeningConnectionStatus { DISABLED, UNKNOWN, DISCONNECTED, CONNECTED }

data class ListeningAppsProjection(
    /** Selected, usable shortcuts in saved order, independent of connection state. */
    val availableApps: List<LaunchableApp>,
    /** The Home section contents. Empty while disabled, unknown, or disconnected. */
    val visibleApps: List<LaunchableApp>,
    /** Stored keys remain in settings until the user removes them. */
    val unavailableKeys: List<AppKey>,
    val status: ListeningConnectionStatus,
)

object ListeningAppsProjector {
    /** Category page suppression does not apply to explicitly selected shortcuts. */
    fun project(
        catalog: AppCatalog,
        customizations: AppCustomizations,
        preferences: ListeningPreferences,
        headsetOutputs: Set<HeadsetKind>?,
    ): ListeningAppsProjection {
        val quietProfiles = catalog.profiles.filter { it.quietModeEnabled }
            .mapTo(HashSet()) { it.serialNumber }
        val usable = customizations.visibleApps(catalog.apps).asSequence()
            .filter { it.enabled && it.key.profileSerial !in quietProfiles }
            .associateBy { it.key }
        val selected = preferences.apps.distinct()
        val available = selected.mapNotNull(usable::get)
        val status = when {
            !preferences.enabled -> ListeningConnectionStatus.DISABLED
            headsetOutputs == null -> ListeningConnectionStatus.UNKNOWN
            headsetOutputs.isEmpty() -> ListeningConnectionStatus.DISCONNECTED
            preferences.kind != HeadsetKind.ANY && preferences.kind !in headsetOutputs ->
                ListeningConnectionStatus.DISCONNECTED
            else -> ListeningConnectionStatus.CONNECTED
        }
        return ListeningAppsProjection(
            availableApps = available,
            visibleApps = if (status == ListeningConnectionStatus.CONNECTED) available else emptyList(),
            unavailableKeys = selected.filterNot(usable::containsKey),
            status = status,
        )
    }
}
