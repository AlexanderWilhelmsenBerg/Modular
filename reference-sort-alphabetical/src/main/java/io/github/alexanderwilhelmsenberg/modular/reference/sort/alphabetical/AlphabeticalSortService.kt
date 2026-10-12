package io.github.alexanderwilhelmsenberg.modular.reference.sort.alphabetical

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import io.github.alexanderwilhelmsenberg.modular.moduleapi.IModularHost
import io.github.alexanderwilhelmsenberg.modular.moduleapi.IModularModuleService
import io.github.alexanderwilhelmsenberg.modular.moduleapi.ModuleContract
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IAppSortCapability
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortRequestParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortResultParcel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * First independently installed reference provider for Modular.
 *
 * This module intentionally owns no Android launcher/package behavior. It
 * receives sortable app projections over Binder and returns an ordered key list.
 */
class AlphabeticalSortService : Service() {
    private val sessions = ConcurrentHashMap<String, ModuleSession>()

    private val moduleBinder = object : IModularModuleService.Stub() {
        override fun getProtocolVersion(): Int =
            ModuleContract.CURRENT_PROTOCOL_VERSION

        override fun getModuleId(): String =
            MODULE_ID

        override fun getModuleVersion(): String =
            MODULE_VERSION

        override fun getCapabilities(): Array<String> =
            arrayOf(StandardCapabilities.AppsSort.wireValue)

        override fun openSession(
            sessionId: String,
            host: IModularHost,
        ) {
            enforceTrustedCoreCaller()

            require(sessionId.isNotBlank()) {
                "sessionId must not be blank"
            }

            val newSession = ModuleSession(
                sessionId = sessionId,
                host = host,
            )

            val previous = sessions.put(sessionId, newSession)
            previous?.revoke()

            try {
                host.asBinder().linkToDeath(
                    newSession.deathRecipient,
                    0,
                )
            } catch (throwable: Throwable) {
                sessions.remove(sessionId, newSession)
                newSession.revoke()
                throw throwable
            }
        }

        override fun closeSession(sessionId: String) {
            enforceTrustedCoreCaller()
            sessions.remove(sessionId)?.revoke()
        }

        override fun getCapabilityBinder(
            sessionId: String,
            capabilityId: String,
        ): IBinder? {
            enforceTrustedCoreCaller()

            val session = sessions[sessionId]
                ?: return null

            if (!session.active.get()) return null

            return when (capabilityId) {
                StandardCapabilities.AppsSort.wireValue ->
                    session.sortCapability.asBinder()

                else -> null
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder =
        moduleBinder

    override fun onDestroy() {
        sessions.values.forEach { session -> session.revoke() }
        sessions.clear()
        super.onDestroy()
    }

    private fun enforceTrustedCoreCaller() {
        val callerUid = Binder.getCallingUid()
        val callerPackages = packageManager
            .getPackagesForUid(callerUid)
            .orEmpty()

        val trusted = callerPackages.any { callerPackage ->
            packageManager.checkSignatures(
                packageName,
                callerPackage,
            ) == PackageManager.SIGNATURE_MATCH
        }

        if (!trusted) {
            throw SecurityException(
                "Module caller is not signed by the trusted signer",
            )
        }
    }

    private inner class ModuleSession(
        private val sessionId: String,
        private val host: IModularHost,
    ) {
        val active = AtomicBoolean(true)

        val deathRecipient = IBinder.DeathRecipient {
            sessions.remove(sessionId, this)
            revoke(unlinkDeathRecipient = false)
        }

        val sortCapability =
            object : IAppSortCapability.Stub() {
                override fun sort(
                    request: AppSortRequestParcel,
                ): AppSortResultParcel {
                    enforceTrustedCoreCaller()

                    if (!active.get()) {
                        throw SecurityException(
                            "Module session is no longer active",
                        )
                    }

                    if (
                        request.items.size >
                        AppSortRequestParcel.MAX_ITEMS
                    ) {
                        return AppSortResultParcel(
                            status =
                                AppSortResultParcel.STATUS_INVALID_ARGUMENT,
                            orderedKeys = emptyList(),
                            message =
                                "Sort request exceeds maximum item count",
                        )
                    }

                    return runCatching {
                        AlphabeticalSorter.sort(
                            items = request.items,
                            localeTag = request.localeTag,
                        )
                    }.fold(
                        onSuccess = { sorted ->
                            AppSortResultParcel(
                                status =
                                    AppSortResultParcel.STATUS_SUCCESS,
                                orderedKeys =
                                    sorted.map { item -> item.key },
                                message = null,
                            )
                        },
                        onFailure = { throwable ->
                            AppSortResultParcel(
                                status =
                                    AppSortResultParcel.STATUS_FAILED,
                                orderedKeys = emptyList(),
                                message = throwable.message
                                    ?: throwable::class.java.simpleName,
                            )
                        },
                    )
                }
            }

        fun revoke(
            unlinkDeathRecipient: Boolean = true,
        ) {
            if (!active.compareAndSet(true, false)) return

            if (unlinkDeathRecipient) {
                runCatching {
                    host.asBinder().unlinkToDeath(
                        deathRecipient,
                        0,
                    )
                }
            }
        }
    }

    companion object {
        const val MODULE_ID =
            "modular.reference.sort.alphabetical"
        const val MODULE_VERSION =
            "0.1.0"
    }
}
