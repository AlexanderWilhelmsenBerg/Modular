package io.github.alexanderwilhelmsenberg.modular.capabilities

import android.content.Context
import android.os.Binder
import io.github.alexanderwilhelmsenberg.modular.apps.AppRepository
import io.github.alexanderwilhelmsenberg.modular.moduleapi.CoreCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.IModularHost
import io.github.alexanderwilhelmsenberg.modular.moduleapi.ModuleContract
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppCatalogInfoParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppCatalogPageParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppKeyParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppLaunchResultParcel
import kotlinx.coroutines.runBlocking

/**
 * Per-session Core Binder exposed to one external module package.
 *
 * Slice 3 enforces session caller identity. Capability grants are a later
 * policy layer and will be enforced here before each capability invocation.
 */
class CoreHostBinder(
    context: Context,
    private val expectedPackage: String,
    private val sessionId: String,
    private val appRepository: AppRepository,
) : IModularHost.Stub() {
    private val packageManager = context.packageManager

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

    override fun getAppCatalogInfo(): AppCatalogInfoParcel {
        enforceSessionCaller()
        return AppCatalogIpcMapper.info(appRepository.catalog.value)
    }

    override fun getAppCatalogPage(
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

    override fun launchApp(key: AppKeyParcel): AppLaunchResultParcel {
        enforceSessionCaller()

        if (key.packageName.isBlank() || key.activityName.isBlank()) {
            return AppLaunchResultParcel(
                status = AppLaunchResultParcel.STATUS_NOT_FOUND,
                message = "Invalid app key",
            )
        }

        val appKey = AppCatalogIpcMapper.fromParcel(key)
        val exists = appRepository.catalog.value.apps.any { it.key == appKey }

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

    private fun enforceSessionCaller() {
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
