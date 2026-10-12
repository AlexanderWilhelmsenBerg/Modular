package io.github.alexanderwilhelmsenberg.modular.capabilities

import android.content.ComponentName
import io.github.alexanderwilhelmsenberg.modular.moduleapi.CapabilityId

enum class CapabilityProviderKind {
    CORE,
    MODULE,
}

data class CapabilityRoute(
    val capability: CapabilityId,
    val providerKind: CapabilityProviderKind,
    val providerId: String,
    val component: ComponentName? = null,
)
