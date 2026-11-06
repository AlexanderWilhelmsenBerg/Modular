package io.github.alexanderwilhelmsenberg.modular.moduleapi;

import android.os.IBinder;
import io.github.alexanderwilhelmsenberg.modular.moduleapi.IModularHost;

/**
 * Root service exposed by an independently installed Modular module APK.
 *
 * Transaction codes 1-4 preserve the v1 discovery ABI.
 * Protocol v2 adds session exchange and capability Binder acquisition.
 * Protocol v3 adds explicit declaration of Core capabilities requested by the
 * module. Providing a capability and requesting Core authority remain separate.
 * Protocol v4 adds a bounded descriptor and instance-specific Binder acquisition.
 * Transaction codes 1-8 remain unchanged.
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

    /**
     * Core capabilities the module asks to consume through IModularHost.
     *
     * This declaration is not authority. Core separately evaluates persistent
     * grants for the module's verified package/signing identity.
     */
    String[] getRequestedCoreCapabilities() = 8;

    /** Returns the same validated descriptor declared in manifest metadata. */
    String getDescriptorJson() = 9;

    /**
     * Returns an endpoint for one Core-owned instance. Core passes normalized
     * configuration JSON after validating it against the descriptor. The
     * endpoint is valid only while its session and instance remain active.
     */
    @nullable IBinder getInstanceCapabilityBinder(
        String sessionId,
        String instanceId,
        String configurationJson,
        String capabilityId
    ) = 10;
}

