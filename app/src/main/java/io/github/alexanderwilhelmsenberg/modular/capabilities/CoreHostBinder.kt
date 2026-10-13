package io.github.alexanderwilhelmsenberg.modular.capabilities

import android.content.Context
import android.os.Binder
import android.os.IBinder
import io.github.alexanderwilhelmsenberg.modular.apps.AppRepository
import io.github.alexanderwilhelmsenberg.modular.moduleapi.CapabilityId
import io.github.alexanderwilhelmsenberg.modular.moduleapi.CoreCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.IModularHost
import io.github.alexanderwilhelmsenberg.modular.moduleapi.ModuleContract
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IAppCatalogCapability
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IAppLaunchCapability
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppCatalogInfoParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppCatalogPageParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppKeyParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppLaunchResultParcel
import io.github.alexanderwilhelmsenberg.modular.security.ModuleAuthorizationContext
import io.github.alexanderwilhelmsenberg.modular.security.ModuleSecurityManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

/**
 * Per-session Core Binder exposed to one external module package.
 *
 * The root Binder only brokers capability Binders. Each capability Binder
 * repeats both the session caller check and the live grant check so leaked
 * Binder references do not become durable authority.
 *
 * A closed module session revokes this object and every capability Binder
 * created by it.
 */
class CoreHostBinder(
    context: Context,
    private val expectedPackage: String,
    private val sessionId: String,
    private val appRepository: AppRepository,
    private val securityManager: ModuleSecurityManager,
    private val authorization: ModuleAuthorizationContext,
) : IModularHost.Stub() {
    private val packageManager = context.packageManager
    private val active = AtomicBoolean(true)

    private val appCatalogCapability =
        object : IAppCatalogCapability.Stub() {
            override fun getInfo(): AppCatalogInfoParcel {
                enforceCapability(CoreCapabilities.AppsCatalog)
                return AppCatalogIpcMapper.info(
                    appRepository.catalog.value,
                )
            }

            override fun getPage(
                expectedGeneration: Long,
                offset: Int,
                limit: Int,
            ): AppCatalogPageParcel {
                enforceCapability(CoreCapabilities.AppsCatalog)
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
            override fun launch(
                key: AppKeyParcel,
            ): AppLaunchResultParcel {
                enforceCapability(CoreCapabilities.AppsLaunch)

                if (
                    key.packageName.isBlank() ||
                    key.activityName.isBlank()
                ) {
                    return AppLaunchResultParcel(
                        status =
                            AppLaunchResultParcel
                                .STATUS_NOT_FOUND,
                        message = "Invalid app key",
                    )
                }

                val appKey =
                    AppCatalogIpcMapper.fromParcel(key)
                val exists =
                    appRepository.catalog.value.apps.any {
                        it.key == appKey
                    }

                if (!exists) {
                    return AppLaunchResultParcel(
                        status =
                            AppLaunchResultParcel
                                .STATUS_NOT_FOUND,
                        message =
                            "App is not present in the current catalog",
                    )
                }

                return runBlocking {
                    appRepository.launch(appKey).fold(
                        onSuccess = {
                            AppLaunchResultParcel(
                                status =
                                    AppLaunchResultParcel
                                        .STATUS_SUCCESS,
                                message = null,
                            )
                        },
                        onFailure = { throwable ->
                            AppLaunchResultParcel(
                                status =
                                    if (
                                        throwable is
                                        SecurityException
                                    ) {
                                        AppLaunchResultParcel
                                            .STATUS_DENIED
                                    } else {
                                        AppLaunchResultParcel
                                            .STATUS_FAILED
                                    },
                                message =
                                    throwable.message
                                        ?: throwable::class.java
                                            .simpleName,
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

        return securityManager
            .effectiveCapabilities(authorization)
            .sortedBy { it.wireValue }
            .map { it.wireValue }
            .toTypedArray()
    }

    override fun getCapabilityBinder(
        capabilityId: String,
    ): IBinder? {
        enforceSessionCaller()

        val capability =
            CapabilityId.parse(capabilityId)
                ?: return null

        if (
            !securityManager.isGranted(
                authorization,
                capability,
            )
        ) {
            return null
        }

        return when (capability) {
            CoreCapabilities.AppsCatalog ->
                appCatalogCapability.asBinder()

            CoreCapabilities.AppsLaunch ->
                appLaunchCapability.asBinder()

            else -> null
        }
    }

    fun revoke() {
        active.set(false)
    }

    private fun enforceCapability(
        capability: CapabilityId,
    ) {
        enforceSessionCaller()

        if (
            !securityManager.isGranted(
                authorization,
                capability,
            )
        ) {
            throw SecurityException(
                "Capability " +
                    capability.wireValue +
                    " is not granted to " +
                    expectedPackage,
            )
        }
    }

    private fun enforceSessionCaller() {
        if (!active.get()) {
            throw SecurityException(
                "Module session $sessionId is no longer active",
            )
        }

        val callingUid = Binder.getCallingUid()
        val callerPackages =
            packageManager
                .getPackagesForUid(callingUid)
                .orEmpty()

        if (expectedPackage !in callerPackages) {
            throw SecurityException(
                "Caller UID $callingUid is not authorized for session " +
                    sessionId,
            )
        }
    }
}
