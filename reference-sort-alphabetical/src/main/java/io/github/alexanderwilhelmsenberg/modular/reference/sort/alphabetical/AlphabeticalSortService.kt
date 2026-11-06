package io.github.alexanderwilhelmsenberg.modular.reference.sort.alphabetical

import android.os.IBinder
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IAppSortCapability
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortRequestParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortResultParcel
import io.github.alexanderwilhelmsenberg.modular.modulesdk.BaseModuleService
import io.github.alexanderwilhelmsenberg.modular.modulesdk.ModuleInstanceContext
import org.json.JSONObject

/** A provider instance reads only its own validated, immutable configuration. */
class AlphabeticalSortService : BaseModuleService() {
    override val descriptorJson: String by lazy {
        resources.openRawResource(R.raw.module_descriptor).bufferedReader().use { it.readText() }
    }

    override fun createCapability(context: ModuleInstanceContext, capabilityId: String): IBinder? {
        if (capabilityId != StandardCapabilities.AppsSort.wireValue) return null
        val reverse = JSONObject(context.normalizedConfigurationJson).getBoolean("reverse")
        return object : IAppSortCapability.Stub() {
            override fun sort(request: AppSortRequestParcel): AppSortResultParcel {
                context.enforceCallingCore()
                if (request.items.size > AppSortRequestParcel.MAX_ITEMS) {
                    return AppSortResultParcel(
                        status = AppSortResultParcel.STATUS_INVALID_ARGUMENT,
                        orderedKeys = emptyList(),
                        message = "Sort request exceeds maximum item count",
                    )
                }
                return runCatching {
                    AlphabeticalSorter.sort(request.items, request.localeTag, reverse)
                }.fold(
                    onSuccess = { sorted ->
                        context.checkActive()
                        AppSortResultParcel(
                            status = AppSortResultParcel.STATUS_SUCCESS,
                            orderedKeys = sorted.map { it.key },
                            message = null,
                        )
                    },
                    onFailure = { failure ->
                        if (!context.isActive) throw SecurityException("Module instance is no longer active")
                        AppSortResultParcel(
                            status = AppSortResultParcel.STATUS_FAILED,
                            orderedKeys = emptyList(),
                            message = failure.message ?: failure.javaClass.simpleName,
                        )
                    },
                )
            }
        }
    }
}
