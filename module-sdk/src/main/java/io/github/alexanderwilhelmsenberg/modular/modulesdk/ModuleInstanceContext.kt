package io.github.alexanderwilhelmsenberg.modular.modulesdk

import android.os.Binder
import android.os.IBinder
import io.github.alexanderwilhelmsenberg.modular.moduleapi.IModularHost
import io.github.alexanderwilhelmsenberg.modular.moduleapi.CoreCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IAppCatalogCapability
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IAppLaunchCapability
import java.util.concurrent.atomic.AtomicBoolean

/** A frozen configuration snapshot for one Core-owned instance. */
class ModuleInstanceContext internal constructor(
    val instanceId: String,
    val normalizedConfigurationJson: String,
    private val host: IModularHost,
    private val trustedCaller: (Int) -> Boolean,
) {
    private val active = AtomicBoolean(true)

    val isActive: Boolean get() = active.get()

    /** Call at the start of every method in a capability AIDL implementation. */
    fun enforceCallingCore() {
        checkActive()
        if (!trustedCaller(Binder.getCallingUid())) {
            throw SecurityException("Untrusted Core caller")
        }
    }

    fun checkActive() {
        if (!active.get()) throw SecurityException("Module instance is no longer active")
    }

    /** Host access never grants authority by itself; Core still checks grants. */
    fun hostCapability(capabilityId: String): IBinder? {
        checkActive()
        return host.getCapabilityBinder(capabilityId)
    }

    fun appCatalog(): IAppCatalogCapability? =
        hostCapability(CoreCapabilities.AppsCatalog.wireValue)?.let(IAppCatalogCapability.Stub::asInterface)

    fun appLaunch(): IAppLaunchCapability? =
        hostCapability(CoreCapabilities.AppsLaunch.wireValue)?.let(IAppLaunchCapability.Stub::asInterface)

    internal fun revoke() {
        active.set(false)
    }
}
