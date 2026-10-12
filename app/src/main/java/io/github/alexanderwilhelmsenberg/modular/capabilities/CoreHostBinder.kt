package io.github.alexanderwilhelmsenberg.modular.capabilities

import android.content.Context
import android.os.Binder
import android.os.IBinder
import io.github.alexanderwilhelmsenberg.modular.apps.AppRepository
import io.github.alexanderwilhelmsenberg.modular.moduleapi.CoreCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.IModularHost
import io.github.alexanderwilhelmsenberg.modular.moduleapi.ModuleContract
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IAppCatalogCapability
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IAppLaunchCapability
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppCatalogInfoParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppCatalogPageParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppKeyParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppLaunchResultParcel
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

/**
 * Per-session Core Binder exposed to one external module package.
 *
 * The root Binder only brokers capability Binders. Each capability Binder
 * repeats the session caller check so a leaked Binder reference does not become
 * authority for another package.
 *
 * A closed module session revokes this object and every capability Binder
 * created by it.
 */
class CoreHostBinder(
    context: Context,
    private val expectedPackage: String,
    private val sessionId: String,
    private val appRepository: AppRepository,
) : IModularHost.Stub() {
    private val packageManager = context.packageManager
    private val active = AtomicBoolean(true)

    private val appCatalogCapability =
        object : IAppCatalogCapability.Stub() {
            override fun getInfo(): AppCatalogInfoParcel {
                enforceSessionCaller()
                return AppCatalogIpcMapper.info(appRepository.catalog.value)
            }

            override fun getPage(
                expectedGeneration: Long,
                offset: Int,
                limit: Int,
            ): AppCatalogPageParcel {
                enforceSessionCaller()
                return AppCatalogIpcMapper.page(
                    catalog = appRepository.catalog.value,
                    expectedGeneration = expectedGeneration,
                    offset = offset,
                    limit = limit,
                )
            }
        }

    private val appLaunchCapability =
        object : IAppLaunchCapability.Stub() {
            override fun launch(key: AppKeyParcel): AppLaunchResultParcel {
                enforceSessionCaller()

                if (key.packageName.isBlank() || key.activityName.isBlank()) {
                    return AppLaunchResultParcel(
                        status = AppLaunchResultParcel.STATUS_NOT_FOUND,
                        message = "Invalid app key",
                    )
                }

                val appKey = AppCatalogIpcMapper.fromParcel(key)
                val exists = appRepository.catalog.value.apps.any {
                    it.key == appKey
                }

                if (!exists) {
                    return AppLaunchResultParcel(
                        status = AppLaunchResultParcel.STATUS_NOT_FOUND,
                        message = "App is not present in the current catalog",
                    )
                }

                return runBlocking {
                    appRepository.launch(appKey).fold(
                        onSuccess = {
                            AppLaunchResultParcel(
                                status = AppLaunchResultParcel.STATUS_SUCCESS,
                                message = null,
                            )
                        },
                        onFailure = { throwable ->
                            AppLaunchResultParcel(
                                status = if (throwable is SecurityException) {
                                    AppLaunchResultParcel.STATUS_DENIED
                                } else {
                                    AppLaunchResultParcel.STATUS_FAILED
                                },
                                message = throwable.message
                                    ?: throwable::class.java.simpleName,
                            )
                        },
                    )
                }
            }
        }

    override fun getHostProtocolVersion(): Int {
        enforceSessionCaller()
        return ModuleContract.HOST_PROTOCOL_VERSION
    }

    override fun getCapabilities(): Array<String> {
        enforceSessionCaller()
        return CoreCapabilities.all
            .map { it.wireValue }
            .toTypedArray()
    }

    override fun getCapabilityBinder(capabilityId: String): IBinder? {
        enforceSessionCaller()

        return when (capabilityId) {
            CoreCapabilities.AppsCatalog.wireValue ->
                appCatalogCapability.asBinder()

            CoreCapabilities.AppsLaunch.wireValue ->
                appLaunchCapability.asBinder()

            else -> null
        }
    }

    fun revoke() {
        active.set(false)
    }

    private fun enforceSessionCaller() {
        if (!active.get()) {
            throw SecurityException(
                "Module session $sessionId is no longer active",
            )
        }

        val callingUid = Binder.getCallingUid()
        val callerPackages = packageManager.getPackagesForUid(callingUid).orEmpty()

        if (expectedPackage !in callerPackages) {
            throw SecurityException(
                "Caller UID $callingUid is not authorized for session " +
                    sessionId,
            )
        }
    }
}
