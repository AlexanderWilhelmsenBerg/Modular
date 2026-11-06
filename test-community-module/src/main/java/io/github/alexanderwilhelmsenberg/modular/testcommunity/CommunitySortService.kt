package io.github.alexanderwilhelmsenberg.modular.testcommunity

import android.os.IBinder
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IAppSortCapability
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortRequestParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortResultParcel
import io.github.alexanderwilhelmsenberg.modular.modulesdk.BaseModuleService
import io.github.alexanderwilhelmsenberg.modular.modulesdk.ModuleInstanceContext
import io.github.alexanderwilhelmsenberg.modular.modulesdk.TrustedCorePolicy
import org.json.JSONObject
import java.util.Locale

/** Separate-signer fixture implementing the public module SDK contract. */
open class CommunitySortService : BaseModuleService() {
    override val descriptorJson: String by lazy {
        resources.openRawResource(R.raw.module_descriptor).bufferedReader().use { it.readText() }
    }

    // The test installs Core first. This captures its signer at first bind and
    // rechecks package, UID and signer on each subsequent Binder call.
    override val trustedCorePolicy: TrustedCorePolicy by lazy {
        TrustedCorePolicy.pinnedToInstalled(
            this,
            setOf(TrustedCorePolicy.CORE_DEBUG_PACKAGE),
        )
    }

    override fun createCapability(
        context: ModuleInstanceContext,
        capabilityId: String,
    ): IBinder? =
        if (capabilityId == StandardCapabilities.AppsSort.wireValue) {
            object : IAppSortCapability.Stub() {
                override fun sort(request: AppSortRequestParcel): AppSortResultParcel {
                    context.enforceCallingCore()
                    if (request.items.size > AppSortRequestParcel.MAX_ITEMS) {
                        return AppSortResultParcel(
                            AppSortResultParcel.STATUS_INVALID_ARGUMENT,
                            emptyList(),
                            "Too many items",
                        )
                    }

                    val reverse = JSONObject(context.normalizedConfigurationJson)
                        .getBoolean("reverse")
                    val ordered = request.items.sortedWith(
                        compareBy(
                            { it.label.lowercase(Locale.forLanguageTag(request.localeTag)) },
                            { it.label },
                            { it.key.packageName },
                            { it.key.activityName },
                            { it.key.profileSerial },
                        ),
                    ).let { if (reverse) it.reversed() else it }
                    val catalogState = runCatching { context.appCatalog()?.info }
                        .getOrNull()
                        ?.let { "granted" }
                        ?: "denied"
                    return AppSortResultParcel(
                        AppSortResultParcel.STATUS_SUCCESS,
                        ordered.map { it.key },
                        "catalog=$catalogState;instance=${context.instanceId}",
                    )
                }
            }.asBinder()
        } else {
            null
        }
}
