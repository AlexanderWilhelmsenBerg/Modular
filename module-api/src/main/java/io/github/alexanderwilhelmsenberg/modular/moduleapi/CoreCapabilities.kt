package io.github.alexanderwilhelmsenberg.modular.moduleapi

object CoreCapabilities {
    val AppsCatalog = CapabilityId("modular.apps.catalog", 1)
    val AppsLaunch = CapabilityId("modular.apps.launch", 1)

    val all: List<CapabilityId> = listOf(
        AppsCatalog,
        AppsLaunch,
    )
}
