package io.github.alexanderwilhelmsenberg.modular.moduleapi

/**
 * Standard capability IDs which may be implemented by external modules.
 */
object StandardCapabilities {
    val AppsSort = CapabilityId("modular.apps.sort", 1)
    val AppsFeatured = CapabilityId("modular.apps.featured", 1)
    /** @deprecated Use [WorkspaceSurface] for independently rendered module UI. */
    @Deprecated("Use WorkspaceSurface")
    val WorkspacePage = CapabilityId("modular.workspace.page", 1)
    /** Independently rendered, interactive Android UI embedded by Core. */
    val WorkspaceSurface = CapabilityId("modular.workspace.surface", 1)
}

