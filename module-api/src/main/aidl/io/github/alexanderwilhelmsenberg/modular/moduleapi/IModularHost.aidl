package io.github.alexanderwilhelmsenberg.modular.moduleapi;

import android.os.IBinder;

/**
 * Per-module-session Binder supplied by Core.
 *
 * The root host only exposes discovery and capability Binder acquisition.
 * Capability behavior lives on narrow versioned AIDL interfaces.
 */
interface IModularHost {
    int getHostProtocolVersion() = 1;
    String[] getCapabilities() = 2;

    @nullable IBinder getCapabilityBinder(String capabilityId) = 3;
}
