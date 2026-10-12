package io.github.alexanderwilhelmsenberg.modular.modules

import android.content.ComponentName

enum class ModuleConnectionStatus {
    DISCOVERED,
    CONNECTING,
    CONNECTED,
    INCOMPATIBLE,
    DISCONNECTED,
    FAILED,
}

data class ModuleRecord(
    val component: ComponentName,
    val moduleId: String? = null,
    val moduleVersion: String? = null,
    val protocolVersion: Int? = null,
    val capabilities: List<String> = emptyList(),
    val status: ModuleConnectionStatus = ModuleConnectionStatus.DISCOVERED,
    val error: String? = null,
) {
    val label: String
        get() = moduleId ?: component.flattenToShortString()
}
