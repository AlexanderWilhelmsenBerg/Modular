package io.github.alexanderwilhelmsenberg.modular.moduleapi;

import android.os.IBinder;
import io.github.alexanderwilhelmsenberg.modular.moduleapi.IModularHost;

/**
 * Root service exposed by an independently installed Modular module APK.
 *
 * Transaction codes 1-4 preserve the v1 discovery ABI.
 * Protocol v2 adds session exchange and capability Binder acquisition.
 */
interface IModularModuleService {
    int getProtocolVersion() = 1;
    String getModuleId() = 2;
    String getModuleVersion() = 3;
    String[] getCapabilities() = 4;

    void openSession(String sessionId, IModularHost host) = 5;
    void closeSession(String sessionId) = 6;

    /**
     * Returns the typed Binder endpoint for an advertised capability.
     * Core converts this raw Binder using the capability-specific AIDL Stub.
     */
    @nullable IBinder getCapabilityBinder(
        String sessionId,
        String capabilityId
    ) = 7;
}
