package io.github.alexanderwilhelmsenberg.modular.moduleapi;

import io.github.alexanderwilhelmsenberg.modular.moduleapi.IModularHost;

/**
 * Root service exposed by an independently installed Modular module APK.
 *
 * v1 methods are the discovery handshake.
 * v2 adds an explicit Core-host session Binder.
 */
interface IModularModuleService {
    int getProtocolVersion();
    String getModuleId();
    String getModuleVersion();
    String[] getCapabilities();

    void openSession(String sessionId, IModularHost host);
    void closeSession(String sessionId);
}
