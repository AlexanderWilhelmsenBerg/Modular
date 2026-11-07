package io.github.alexanderwilhelmsenberg.modular.addon.sort.mostused

import android.app.usage.UsageStatsManager
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.UserManager
import io.github.alexanderwilhelmsenberg.modular.moduleapi.FeaturedAppsContract
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IAppSortCapability
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IFeaturedAppsCapability
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortRequestParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortResultParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppKeyParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortItemParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedAppsRequestParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedAppsResultParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedMutationResultParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedPinOrderRequestParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedPinRequestParcel
import io.github.alexanderwilhelmsenberg.modular.modulesdk.BaseModuleService
import io.github.alexanderwilhelmsenberg.modular.modulesdk.ModuleInstanceContext
import org.json.JSONObject

class MostUsedSortService : BaseModuleService() {
    private val pinLock = Any()
    private val pinStore by lazy { PinStore(filesDir) }

    override val descriptorJson: String by lazy {
        resources.openRawResource(R.raw.module_descriptor).bufferedReader().use { it.readText() }
    }

    override fun createCapability(context: ModuleInstanceContext, capabilityId: String): IBinder? {
        val config = JSONObject(context.normalizedConfigurationJson)
        val days = config.getInt("days")
        return when (capabilityId) {
            StandardCapabilities.AppsSort.wireValue -> legacySortCapability(context, days)
            StandardCapabilities.AppsFeatured.wireValue -> featuredCapability(
                context, days, config.optInt("suggestedCount", 8).coerceIn(0, 20),
            )
            else -> null
        }
    }

    private fun legacySortCapability(context: ModuleInstanceContext, days: Int): IBinder =
        object : IAppSortCapability.Stub() {
            override fun sort(request: AppSortRequestParcel): AppSortResultParcel {
                context.enforceCallingCore()
                if (request.items.size > AppSortRequestParcel.MAX_ITEMS) {
                    return failed(
                        AppSortResultParcel.STATUS_INVALID_ARGUMENT,
                        "Sort request exceeds maximum item count",
                    )
                }
                // A capability Binder call arrives with Core's calling identity. After
                // authenticating Core, use this add-on's identity for its own app-op and
                // UsageStatsManager calls; Core's Usage Access grant is unrelated.
                val identity = Binder.clearCallingIdentity()
                return try {
                    if (!UsageAccess.isGranted(this@MostUsedSortService)) {
                        return failed(
                            AppSortResultParcel.STATUS_FAILED,
                            "Usage access is off for Most Used. Open Most Used settings to enable it.",
                        )
                    }
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
                            "Usage access is off for Most Used. Open Most Used settings to enable it.",
                        )
                    }
                    AppSortResultParcel(
                        status = AppSortResultParcel.STATUS_SUCCESS,
                        orderedKeys = sorted.map { it.key },
                        message = if (weights.values.none { it > 0L }) {
                            "Android reported no foreground time for these apps in the selected period."
                        } else null,
                    )
                } catch (_: RuntimeException) {
                    if (!context.isActive) throw SecurityException("Module instance is no longer active")
                    failed(AppSortResultParcel.STATUS_FAILED, "Usage statistics are unavailable.")
                } finally {
                    Binder.restoreCallingIdentity(identity)
                }
            }
        }

    private fun featuredCapability(context: ModuleInstanceContext, days: Int, suggestedCount: Int): IBinder =
        object : IFeaturedAppsCapability.Stub() {
            private var latestCatalogRevision: Long? = null
            private var latestCatalogKeys: Set<AppKeyParcel> = emptySet()

            override fun getFeaturedApps(request: FeaturedAppsRequestParcel): FeaturedAppsResultParcel {
                context.enforceCallingCore()
                if (runCatching { FeaturedAppsContract.validateRequest(request) }.isFailure) {
                    return featuredFailure(request, FeaturedAppsResultParcel.STATUS_INVALID_ARGUMENT, "Invalid catalog request")
                }
                val pins = try {
                    synchronized(pinLock) {
                        context.checkActive()
                        val current = pinStore.read(context.instanceId)
                        latestCatalogRevision = request.catalogRevision
                        latestCatalogKeys = request.items.mapTo(HashSet()) { it.key }
                        current
                    }
                } catch (_: Exception) {
                    context.checkActive()
                    return featuredFailure(request, FeaturedAppsResultParcel.STATUS_FAILED, "Saved pins could not be read. Open Most Used settings to retry.")
                }

                // Binder arrives as Core; app-op and usage queries must run as this add-on.
                val identity = Binder.clearCallingIdentity()
                val usage = try {
                    if (!UsageAccess.isGranted(this@MostUsedSortService)) {
                        UsageResult(FeaturedAppsResultParcel.STATE_PERMISSION_REQUIRED, emptyList(),
                            "Usage Access is off. Pin apps or enable access in Most Used settings.")
                    } else {
                        val weights = foregroundWeights(request.items, days)
                        if (!UsageAccess.isGranted(this@MostUsedSortService)) {
                            UsageResult(FeaturedAppsResultParcel.STATE_PERMISSION_REQUIRED, emptyList(),
                                "Usage Access is off. Pin apps or enable access in Most Used settings.")
                        } else {
                            val ownProfile = ownProfileSerial()
                            val suggested = MostUsedSorter.suggestions(
                                request.items, request.localeTag, ownProfile, weights,
                                pins.keys.toSet(), suggestedCount,
                            )
                            val hasHistory = request.items.any { item ->
                                item.key.profileSerial == ownProfile &&
                                    (weights[item.key.packageName] ?: 0L) > 0L
                            }
                            UsageResult(
                                if (hasHistory) FeaturedAppsResultParcel.STATE_READY
                                else FeaturedAppsResultParcel.STATE_NO_HISTORY,
                                suggested,
                                if (hasHistory) null
                                else "Android reported no foreground time for these apps in the selected period.",
                            )
                        }
                    }
                } catch (_: RuntimeException) {
                    context.checkActive()
                    if (!UsageAccess.isGranted(this@MostUsedSortService)) {
                        return FeaturedAppsResultParcel(
                            request.requestId, request.catalogRevision, pins.revision,
                            FeaturedAppsResultParcel.STATUS_SUCCESS,
                            FeaturedAppsResultParcel.STATE_PERMISSION_REQUIRED,
                            pins.keys, emptyList(),
                            "Usage Access is off. Pin apps or enable access in Most Used settings.",
                        )
                    }
                    return featuredFailure(request, FeaturedAppsResultParcel.STATUS_FAILED, "Usage statistics are unavailable.")
                } finally {
                    Binder.restoreCallingIdentity(identity)
                }
                context.checkActive()
                return FeaturedAppsResultParcel(
                    requestId = request.requestId,
                    catalogRevision = request.catalogRevision,
                    featureRevision = pins.revision,
                    status = FeaturedAppsResultParcel.STATUS_SUCCESS,
                    featureState = usage.state,
                    pinnedKeys = pins.keys,
                    suggestedKeys = usage.suggestions,
                    message = usage.message,
                )
            }

            override fun setPinned(request: FeaturedPinRequestParcel): FeaturedMutationResultParcel {
                context.enforceCallingCore()
                if (runCatching { FeaturedAppsContract.validatePinRequest(request) }.isFailure || !PinStore.validKey(request.key)) {
                    return mutation(request.requestId, FeaturedMutationResultParcel.STATUS_INVALID_ARGUMENT, 0, "Invalid pin request")
                }
                return try {
                    synchronized(pinLock) {
                        context.checkActive()
                        val current = pinStore.read(context.instanceId)
                        // A repeated set-state request is safe even after its first acknowledgement is lost.
                        if ((request.key in current.keys) == request.pinned) {
                            return@synchronized mutation(request.requestId, FeaturedMutationResultParcel.STATUS_SUCCESS, current.revision, null)
                        }
                        if (request.expectedFeatureRevision != current.revision) {
                            return@synchronized mutation(request.requestId, FeaturedMutationResultParcel.STATUS_CONFLICT, current.revision, "Pins changed. Refresh and retry.")
                        }
                        if (request.pinned && (request.catalogRevision != latestCatalogRevision || request.key !in latestCatalogKeys)) {
                            return@synchronized mutation(request.requestId, FeaturedMutationResultParcel.STATUS_INVALID_ARGUMENT, current.revision, "App is not in the current catalog.")
                        }
                        if (!request.pinned && request.key !in current.keys) {
                            return@synchronized mutation(request.requestId, FeaturedMutationResultParcel.STATUS_INVALID_ARGUMENT, current.revision, "Pin is not saved.")
                        }
                        val changed = current.set(request.key, request.pinned)
                            ?: return@synchronized mutation(request.requestId, FeaturedMutationResultParcel.STATUS_INVALID_ARGUMENT, current.revision, "Pin limit reached.")
                        context.checkActive()
                        pinStore.write(context.instanceId, changed)
                        context.checkActive()
                        mutation(request.requestId, FeaturedMutationResultParcel.STATUS_SUCCESS, changed.revision, null)
                    }
                } catch (_: Exception) {
                    context.checkActive()
                    mutation(request.requestId, FeaturedMutationResultParcel.STATUS_FAILED, 0, "Pins could not be saved. Retry.")
                }
            }

            override fun replacePinOrder(request: FeaturedPinOrderRequestParcel): FeaturedMutationResultParcel {
                context.enforceCallingCore()
                if (runCatching { FeaturedAppsContract.validatePinOrderRequest(request) }.isFailure) {
                    return mutation(request.requestId, FeaturedMutationResultParcel.STATUS_INVALID_ARGUMENT, 0, "Invalid pin order")
                }
                return try {
                    synchronized(pinLock) {
                        context.checkActive()
                        val current = pinStore.read(context.instanceId)
                        if (request.orderedKeys == current.keys) {
                            return@synchronized mutation(request.requestId, FeaturedMutationResultParcel.STATUS_SUCCESS, current.revision, null)
                        }
                        if (request.expectedFeatureRevision != current.revision) {
                            return@synchronized mutation(request.requestId, FeaturedMutationResultParcel.STATUS_CONFLICT, current.revision, "Pins changed. Refresh and retry.")
                        }
                        val changed = current.reorder(request.orderedKeys)
                            ?: return@synchronized mutation(request.requestId, FeaturedMutationResultParcel.STATUS_INVALID_ARGUMENT, current.revision, "Order must contain every saved pin once.")
                        context.checkActive()
                        pinStore.write(context.instanceId, changed)
                        context.checkActive()
                        mutation(request.requestId, FeaturedMutationResultParcel.STATUS_SUCCESS, changed.revision, null)
                    }
                } catch (_: Exception) {
                    context.checkActive()
                    mutation(request.requestId, FeaturedMutationResultParcel.STATUS_FAILED, 0, "Pins could not be saved. Retry.")
                }
            }
        }

    private fun foregroundWeights(items: List<AppSortItemParcel>, days: Int): Map<String, Long> {
        val now = System.currentTimeMillis()
        val manager = getSystemService(UsageStatsManager::class.java)
            ?: throw IllegalStateException("Usage statistics service unavailable")
        val supplied = items.mapTo(HashSet()) { it.key.packageName }
        return manager.queryAndAggregateUsageStats(now - days * MILLIS_PER_DAY, now)
            .orEmpty().filterKeys { it in supplied }
            .mapValues { (_, stats) -> stats.totalTimeInForeground.coerceAtLeast(0L) }
    }

    private fun ownProfileSerial(): Long = getSystemService(UserManager::class.java)
        ?.getSerialNumberForUser(Process.myUserHandle())?.takeIf { it >= 0L }
        ?: throw IllegalStateException("Current profile unavailable")

    private fun featuredFailure(request: FeaturedAppsRequestParcel, status: Int, message: String) =
        FeaturedAppsResultParcel(request.requestId, request.catalogRevision, 0, status,
            FeaturedAppsResultParcel.STATE_NO_HISTORY, emptyList(), emptyList(), message)

    private fun mutation(requestId: String, status: Int, revision: Long, message: String?) =
        FeaturedMutationResultParcel(requestId, status, revision, message)

    private data class UsageResult(val state: Int, val suggestions: List<AppKeyParcel>, val message: String?)

    private fun failed(status: Int, message: String) = AppSortResultParcel(
        status = status,
        orderedKeys = emptyList(),
        message = message,
    )

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L
    }
}
