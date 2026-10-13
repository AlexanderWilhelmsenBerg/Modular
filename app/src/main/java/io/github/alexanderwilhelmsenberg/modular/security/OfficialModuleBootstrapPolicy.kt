package io.github.alexanderwilhelmsenberg.modular.security

import io.github.alexanderwilhelmsenberg.modular.moduleapi.CapabilityId
import io.github.alexanderwilhelmsenberg.modular.moduleapi.CoreCapabilities

/**
 * Deliberately reviewed bootstrap authority for official same-signer modules.
 *
 * This list must never be derived from CoreCapabilities.all. Adding a new Core
 * capability therefore defaults to no bootstrap authority until this policy is
 * explicitly changed and reviewed.
 */
object OfficialModuleBootstrapPolicy {
    val capabilities: Set<CapabilityId> = setOf(
        CoreCapabilities.AppsCatalog,
        CoreCapabilities.AppsLaunch,
    )
}
