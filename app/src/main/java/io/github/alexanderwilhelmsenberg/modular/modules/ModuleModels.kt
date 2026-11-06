package io.github.alexanderwilhelmsenberg.modular.modules

import android.content.ComponentName
import io.github.alexanderwilhelmsenberg.modular.moduleapi.ModuleDescriptor

enum class ModuleConnectionStatus {
    DISCOVERED,
    CONNECTING,
    CONNECTED,
    INCOMPATIBLE,
    DISCONNECTED,
    FAILED,
    DISABLED,
}

data class ModuleRecord(
    val component: ComponentName,
    val moduleId: String? = null,
    val moduleVersion: String? = null,
    val protocolVersion: Int? = null,
    val capabilities: List<String> = emptyList(),
    val requestedCoreCapabilities: List<String> = emptyList(),
    val sessionId: String? = null,
    val status: ModuleConnectionStatus = ModuleConnectionStatus.DISCOVERED,
    val enabled: Boolean = true,
    val error: String? = null,
    val descriptor: ModuleDescriptor? = null,
    val descriptorError: String? = null,
    val descriptorFingerprint: String? = null,
) {
    val label: String
        get() = descriptor?.displayName?.takeIf { it.isNotBlank() }
            ?: moduleId ?: component.flattenToShortString()
}

