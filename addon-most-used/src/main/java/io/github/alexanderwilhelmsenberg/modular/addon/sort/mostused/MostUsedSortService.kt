package io.github.alexanderwilhelmsenberg.modular.addon.sort.mostused

import android.app.usage.UsageStatsManager
import android.os.IBinder
import android.os.Process
import android.os.UserManager
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IAppSortCapability
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortRequestParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortResultParcel
import io.github.alexanderwilhelmsenberg.modular.modulesdk.BaseModuleService
import io.github.alexanderwilhelmsenberg.modular.modulesdk.ModuleInstanceContext
import org.json.JSONObject

class MostUsedSortService : BaseModuleService() {
    override val descriptorJson: String by lazy {
        resources.openRawResource(R.raw.module_descriptor).bufferedReader().use { it.readText() }
    }

    override fun createCapability(context: ModuleInstanceContext, capabilityId: String): IBinder? {
        if (capabilityId != StandardCapabilities.AppsSort.wireValue) return null
        val days = JSONObject(context.normalizedConfigurationJson).getInt("days")
        return object : IAppSortCapability.Stub() {
            override fun sort(request: AppSortRequestParcel): AppSortResultParcel {
                context.enforceCallingCore()
                if (request.items.size > AppSortRequestParcel.MAX_ITEMS) {
                    return failed(
                        AppSortResultParcel.STATUS_INVALID_ARGUMENT,
                        "Sort request exceeds maximum item count",
                    )
                }
                if (!UsageAccess.isGranted(this@MostUsedSortService)) {
                    return failed(
                        AppSortResultParcel.STATUS_FAILED,
                        "Usage access is off. Enable it in Most Used settings.",
                    )
                }

                return try {
                    // Android returns aggregate package records whose underlying intervals
                    // overlap this rolling window. These are approximate window totals.
                    val now = System.currentTimeMillis()
                    val start = now - days * MILLIS_PER_DAY
                    val manager = getSystemService(UsageStatsManager::class.java)
                        ?: throw IllegalStateException("Usage statistics service unavailable")
                    val ownProfileSerial = getSystemService(UserManager::class.java)
                        ?.getSerialNumberForUser(Process.myUserHandle())
                        ?: throw IllegalStateException("Current profile unavailable")
                    if (ownProfileSerial < 0L) {
                        throw IllegalStateException("Current profile unavailable")
                    }
                    val suppliedPackages = request.items.mapTo(HashSet()) { it.key.packageName }
                    val weights = manager.queryAndAggregateUsageStats(start, now)
                        .orEmpty()
                        .filterKeys { it in suppliedPackages }
                        .mapValues { (_, stats) -> stats.totalTimeInForeground.coerceAtLeast(0L) }
                    val sorted = MostUsedSorter.sort(
                        request.items,
                        request.localeTag,
                        ownProfileSerial,
                        weights,
                    )
                    context.checkActive()
                    if (!UsageAccess.isGranted(this@MostUsedSortService)) {
                        return failed(
                            AppSortResultParcel.STATUS_FAILED,
                            "Usage access is off. Enable it in Most Used settings.",
                        )
                    }
                    AppSortResultParcel(
                        status = AppSortResultParcel.STATUS_SUCCESS,
                        orderedKeys = sorted.map { it.key },
                        message = null,
                    )
                } catch (_: RuntimeException) {
                    if (!context.isActive) throw SecurityException("Module instance is no longer active")
                    failed(AppSortResultParcel.STATUS_FAILED, "Usage statistics are unavailable.")
                }
            }
        }
    }

    private fun failed(status: Int, message: String) = AppSortResultParcel(
        status = status,
        orderedKeys = emptyList(),
        message = message,
    )

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L
    }
}
