package io.github.alexanderwilhelmsenberg.modular.moduleapi

/**
 * Public discovery and compatibility constants for Modular modules.
 */
object ModuleContract {
    const val SERVICE_ACTION =
        "io.github.alexanderwilhelmsenberg.modular.action.MODULE"

    const val CURRENT_PROTOCOL_VERSION = 1
    const val MIN_SUPPORTED_PROTOCOL_VERSION = 1

    fun isProtocolSupported(protocolVersion: Int): Boolean =
        protocolVersion in MIN_SUPPORTED_PROTOCOL_VERSION..CURRENT_PROTOCOL_VERSION
}
