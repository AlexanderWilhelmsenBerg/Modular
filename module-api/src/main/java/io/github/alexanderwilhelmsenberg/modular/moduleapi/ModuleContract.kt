package io.github.alexanderwilhelmsenberg.modular.moduleapi

/**
 * Public discovery and compatibility constants for Modular modules.
 */
object ModuleContract {
    const val SERVICE_ACTION =
        "io.github.alexanderwilhelmsenberg.modular.action.MODULE"

    /**
     * Protocol v1: discovery/metadata handshake only.
     * Protocol v2: adds Core-host session Binder exchange.
     * Protocol v3: adds requested Core capability declaration.
     */
    const val CURRENT_PROTOCOL_VERSION = 3
    const val MIN_SUPPORTED_PROTOCOL_VERSION = 1
    const val SESSION_PROTOCOL_VERSION = 2
    const val CORE_CAPABILITY_REQUEST_PROTOCOL_VERSION = 3

    const val HOST_PROTOCOL_VERSION = 1

    fun isProtocolSupported(protocolVersion: Int): Boolean =
        protocolVersion in
            MIN_SUPPORTED_PROTOCOL_VERSION..CURRENT_PROTOCOL_VERSION

    fun supportsSessions(protocolVersion: Int): Boolean =
        protocolVersion >= SESSION_PROTOCOL_VERSION &&
            isProtocolSupported(protocolVersion)

    fun supportsCoreCapabilityRequests(
        protocolVersion: Int,
    ): Boolean =
        protocolVersion >=
            CORE_CAPABILITY_REQUEST_PROTOCOL_VERSION &&
            isProtocolSupported(protocolVersion)
}
