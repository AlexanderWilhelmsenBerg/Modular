package io.github.alexanderwilhelmsenberg.modular.modulesdk

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import io.github.alexanderwilhelmsenberg.modular.moduleapi.IModularHost
import io.github.alexanderwilhelmsenberg.modular.moduleapi.IModularModuleService
import io.github.alexanderwilhelmsenberg.modular.moduleapi.ModuleContract
import io.github.alexanderwilhelmsenberg.modular.moduleapi.ModuleDescriptor
import io.github.alexanderwilhelmsenberg.modular.moduleapi.ModuleDescriptorCodec

/**
 * Base class for a protocol-v4 module. The subclass supplies capability Stubs
 * and calls [ModuleInstanceContext.enforceCallingCore] in every Stub method.
 */
abstract class BaseModuleService : Service() {
    protected abstract val descriptorJson: String
    protected open val trustedCorePolicy: TrustedCorePolicy = TrustedCorePolicy.officialSameSigner()

    /** Return a fresh endpoint for the context, or null for an unsupported ID. */
    protected abstract fun createCapability(context: ModuleInstanceContext, capabilityId: String): IBinder?

    private val lock = Any()
    private val sessions = BoundedResources<String, Session>(MAX_SESSIONS) { it.revoke() }
    private val rawDescriptorJson: String by lazy { descriptorJson }
    private val descriptor: ModuleDescriptor by lazy { ModuleDescriptorCodec.parse(rawDescriptorJson) }

    private inner class Session(
        val id: String,
        val ownerUid: Int,
        val host: IModularHost,
    ) {
        val instances = BoundedResources<String, Instance>(MAX_INSTANCES_PER_SESSION) { it.context.revoke() }
        @Volatile var active = true
        val deathRecipient = IBinder.DeathRecipient {
            synchronized(lock) {
                if (sessions[id] === this) {
                    sessions.removeIfSame(id, this)
                }
            }
        }

        fun revoke(unlink: Boolean = true) {
            if (!active) return
            active = false
            instances.clear()
            if (unlink) runCatching { host.asBinder().unlinkToDeath(deathRecipient, 0) }
        }
    }

    private class Instance(
        val normalizedConfigurationJson: String,
        val context: ModuleInstanceContext,
        val capabilities: MutableMap<String, IBinder> = LinkedHashMap(),
    )

    private val moduleBinder = object : IModularModuleService.Stub() {
        override fun getProtocolVersion(): Int = ModuleContract.CURRENT_PROTOCOL_VERSION
        override fun getModuleId(): String = descriptor.moduleId
        override fun getModuleVersion(): String = descriptor.moduleVersion
        override fun getCapabilities(): Array<String> = descriptor.providedCapabilities.toTypedArray()
        override fun getRequestedCoreCapabilities(): Array<String> =
            descriptor.requestedCoreCapabilities.toTypedArray()
        override fun getDescriptorJson(): String {
            descriptor // Fail closed if the static descriptor is invalid.
            return rawDescriptorJson
        }

        override fun openSession(sessionId: String, host: IModularHost) {
            val uid = requireTrustedCaller()
            require(sessionId.isNotBlank() && sessionId.length <= MAX_ID_LENGTH) { "Invalid session ID" }
            synchronized(lock) {
                if (sessions[sessionId]?.ownerUid?.let { it != uid } == true) {
                    throw SecurityException("Session owner changed")
                }
                if (sessions[sessionId] == null && sessions.size >= MAX_SESSIONS) {
                    throw IllegalStateException("Session limit reached")
                }
                val replacement = Session(sessionId, uid, host)
                host.asBinder().linkToDeath(replacement.deathRecipient, 0)
                sessions.put(sessionId, replacement)
                if (!host.asBinder().isBinderAlive) {
                    sessions.removeIfSame(sessionId, replacement)
                    throw SecurityException("Core host died during session setup")
                }
            }
        }

        override fun closeSession(sessionId: String) {
            val uid = requireTrustedCaller()
            synchronized(lock) {
                val session = sessions[sessionId] ?: return
                if (session.ownerUid != uid) throw SecurityException("Session owner changed")
                sessions.remove(sessionId)
            }
        }

        override fun getCapabilityBinder(sessionId: String, capabilityId: String): IBinder? =
            getInstanceCapabilityBinder(sessionId, LEGACY_INSTANCE_ID, "{}", capabilityId)

        override fun getInstanceCapabilityBinder(
            sessionId: String,
            instanceId: String,
            configurationJson: String,
            capabilityId: String,
        ): IBinder? {
            val uid = requireTrustedCaller()
            if (instanceId.isBlank() || instanceId.length > MAX_ID_LENGTH) {
                throw IllegalArgumentException("Invalid instance ID")
            }
            if (capabilityId !in descriptor.providedCapabilities) return null
            val normalized = ModuleDescriptorCodec.normalizeConfiguration(descriptor, configurationJson)
            synchronized(lock) {
                val session = sessions[sessionId] ?: return null
                if (!session.active || session.ownerUid != uid) {
                    throw SecurityException("Session is no longer owned by caller")
                }
                val existing = session.instances[instanceId]
                if (existing != null && existing.normalizedConfigurationJson != normalized) {
                    session.instances.remove(instanceId)
                }
                val instance = session.instances[instanceId] ?: run {
                    val context = ModuleInstanceContext(instanceId, normalized, session.host) { caller ->
                        caller == session.ownerUid && session.active && trustedCorePolicy.isTrusted(this@BaseModuleService, caller)
                    }
                    Instance(normalized, context).also { session.instances.put(instanceId, it) }
                }
                return instance.capabilities[capabilityId] ?: createCapability(instance.context, capabilityId)?.also {
                    if (instance.capabilities.size >= MAX_CAPABILITIES_PER_INSTANCE) {
                        throw IllegalStateException("Capability limit reached")
                    }
                    instance.capabilities[capabilityId] = it
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = moduleBinder

    override fun onDestroy() {
        synchronized(lock) {
            sessions.clear()
        }
        super.onDestroy()
    }

    private fun requireTrustedCaller(): Int = Binder.getCallingUid().also { uid ->
        if (!trustedCorePolicy.isTrusted(this, uid)) throw SecurityException("Untrusted Core caller")
    }

    companion object {
        const val MAX_SESSIONS = 8
        const val MAX_INSTANCES_PER_SESSION = 64
        const val MAX_CAPABILITIES_PER_INSTANCE = 32
        const val MAX_ID_LENGTH = 128
        private const val LEGACY_INSTANCE_ID = "__legacy__"
    }
}
