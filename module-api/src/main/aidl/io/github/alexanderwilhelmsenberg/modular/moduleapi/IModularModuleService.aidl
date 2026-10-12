package io.github.alexanderwilhelmsenberg.modular.moduleapi;

/**
 * Minimal v1 wire contract between Modular Core and an external module APK.
 *
 * Keep this interface deliberately small. Capability-specific protocols belong
 * in separately versioned contracts rather than growing this handshake forever.
 */
interface IModularModuleService {
    int getProtocolVersion();
    String getModuleId();
    String getModuleVersion();
    String[] getCapabilities();
}
