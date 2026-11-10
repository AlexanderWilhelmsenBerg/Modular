package io.github.alexanderwilhelmsenberg.modular.moduleapi

/**
 * Standard capability IDs which may be implemented by external modules.
 */
object StandardCapabilities {
    val AppsSort = CapabilityId("modular.apps.sort", 1)
    val AppsFeatured = CapabilityId("modular.apps.featured", 1)
    /** A bounded, data-only page rendered by a native Core-approved renderer. */
    val WorkspacePage = CapabilityId("modular.workspace.page", 1)
}

