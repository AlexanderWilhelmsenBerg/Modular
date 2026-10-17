package io.github.alexanderwilhelmsenberg.modular.reference.home.minimal

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import io.github.alexanderwilhelmsenberg.modular.moduleapi.IModularHost
import io.github.alexanderwilhelmsenberg.modular.moduleapi.IModularModuleService
import io.github.alexanderwilhelmsenberg.modular.moduleapi.ModuleContract
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.ISurfaceHomeCapability
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.SurfaceActionResultParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.SurfaceDocumentParcel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MinimalHomeService : Service() {
    private val sessions =
        ConcurrentHashMap<String, ModuleSession>()
    private val actionCounts =
        ConcurrentHashMap<String, AtomicInteger>()

    private val moduleBinder =
        object : IModularModuleService.Stub() {
            override fun getProtocolVersion(): Int =
                ModuleContract.CURRENT_PROTOCOL_VERSION

            override fun getModuleId(): String =
                MODULE_ID

            override fun getModuleVersion(): String =
                MODULE_VERSION

            override fun getCapabilities(): Array<String> =
                arrayOf(
                    StandardCapabilities
                        .SurfaceHome
                        .wireValue,
                )

            override fun getRequestedCoreCapabilities():
                Array<String> =
                emptyArray()

            override fun openSession(
                sessionId: String,
                host: IModularHost,
            ) {
                enforceTrustedCoreCaller()
                require(sessionId.isNotBlank()) {
                    "sessionId must not be blank"
                }

                val newSession =
                    ModuleSession(sessionId, host)
                val previous =
                    sessions.put(sessionId, newSession)
                previous?.revoke()

                try {
                    host.asBinder().linkToDeath(
                        newSession.deathRecipient,
                        0,
                    )
                } catch (throwable: Throwable) {
                    sessions.remove(
                        sessionId,
                        newSession,
                    )
                    newSession.revoke()
                    throw throwable
                }
            }

            override fun closeSession(
                sessionId: String,
            ) {
                enforceTrustedCoreCaller()
                sessions.remove(sessionId)?.revoke()
            }

            override fun getCapabilityBinder(
                sessionId: String,
                capabilityId: String,
            ): IBinder? {
                enforceTrustedCoreCaller()

                val session =
                    sessions[sessionId]
                        ?: return null

                if (!session.active.get()) {
                    return null
                }

                return when (capabilityId) {
                    StandardCapabilities
                        .SurfaceHome
                        .wireValue ->
                        session.surfaceCapability
                            .asBinder()

                    else -> null
                }
            }
        }

    override fun onBind(intent: Intent?): IBinder =
        moduleBinder

    override fun onDestroy() {
        sessions.values.forEach { session ->
            session.revoke()
        }
        sessions.clear()
        super.onDestroy()
    }

    private fun enforceTrustedCoreCaller() {
        val callerUid = Binder.getCallingUid()
        val callerPackages =
            packageManager
                .getPackagesForUid(callerUid)
                .orEmpty()

        val trusted =
            callerPackages.any { callerPackage ->
                packageManager.checkSignatures(
                    packageName,
                    callerPackage,
                ) ==
                    PackageManager.SIGNATURE_MATCH
            }

        if (!trusted) {
            throw SecurityException(
                "Module caller is not signed by " +
                    "the trusted signer",
            )
        }
    }

    private inner class ModuleSession(
        private val sessionId: String,
        private val host: IModularHost,
    ) {
        val active = AtomicBoolean(true)

        val deathRecipient =
            IBinder.DeathRecipient {
                sessions.remove(
                    sessionId,
                    this,
                )
                revoke(
                    unlinkDeathRecipient = false,
                )
            }

        val surfaceCapability =
            object :
                ISurfaceHomeCapability.Stub() {
                override fun render(
                    instanceId: String,
                ): SurfaceDocumentParcel {
                    enforceTrustedCoreCaller()
                    enforceActive()
                    require(
                        instanceId.isNotBlank(),
                    ) {
                        "instanceId must not be blank"
                    }

                    val count =
                        actionCounts[instanceId]
                            ?.get()
                            ?: 0

                    return MinimalHomeSurface
                        .document(count)
                }

                override fun performAction(
                    instanceId: String,
                    actionId: String,
                ): SurfaceActionResultParcel {
                    enforceTrustedCoreCaller()
                    enforceActive()

                    if (instanceId.isBlank()) {
                        return SurfaceActionResultParcel(
                            status =
                                SurfaceActionResultParcel
                                    .STATUS_INVALID_INSTANCE,
                            message =
                                "instanceId must not be blank",
                        )
                    }

                    return when (actionId) {
                        MinimalHomeSurface.ACTION_PING -> {
                            actionCounts
                                .computeIfAbsent(
                                    instanceId,
                                ) {
                                    AtomicInteger()
                                }
                                .incrementAndGet()

                            SurfaceActionResultParcel(
                                status =
                                    SurfaceActionResultParcel
                                        .STATUS_SUCCESS,
                            )
                        }

                        else ->
                            SurfaceActionResultParcel(
                                status =
                                    SurfaceActionResultParcel
                                        .STATUS_UNKNOWN_ACTION,
                                message =
                                    "Unknown action " +
                                        actionId,
                            )
                    }
                }
            }

        fun revoke(
            unlinkDeathRecipient: Boolean = true,
        ) {
            if (
                !active.compareAndSet(
                    true,
                    false,
                )
            ) {
                return
            }

            if (unlinkDeathRecipient) {
                runCatching {
                    host.asBinder()
                        .unlinkToDeath(
                            deathRecipient,
                            0,
                        )
                }
            }
        }

        private fun enforceActive() {
            if (!active.get()) {
                throw SecurityException(
                    "Module session is no longer active",
                )
            }
        }
    }

    companion object {
        const val MODULE_ID =
            "modular.reference.home.minimal"
        const val MODULE_VERSION = "0.1.0"
    }
}
