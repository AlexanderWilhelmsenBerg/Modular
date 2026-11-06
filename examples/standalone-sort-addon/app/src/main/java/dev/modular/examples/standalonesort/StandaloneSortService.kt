package dev.modular.examples.standalonesort

import android.os.IBinder
import dev.modular.examples.standalonesort.R
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IAppSortCapability
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortRequestParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortResultParcel
import io.github.alexanderwilhelmsenberg.modular.modulesdk.BaseModuleService
import io.github.alexanderwilhelmsenberg.modular.modulesdk.ModuleInstanceContext
import io.github.alexanderwilhelmsenberg.modular.modulesdk.TrustedCorePolicy
import java.text.Collator
import java.util.Locale
import org.json.JSONObject

/** An independently signed APK using only published module artifacts. */
class StandaloneSortService : BaseModuleService() {
    override val descriptorJson: String by lazy {
        resources.openRawResource(R.raw.module_descriptor).bufferedReader().use { it.readText() }
    }

    // These are certificate SHA-256 fingerprints of the owner's distributed
    // Core APKs, not signing keys. Replace them when targeting another Core build.
    override val trustedCorePolicy: TrustedCorePolicy = TrustedCorePolicy.pinned(
        mapOf(
            TrustedCorePolicy.CORE_RELEASE_PACKAGE to setOf(
                "631f4dcb3498006ae23755926ce3d4ac859fd9d1eb5806c5ca28d2de041f4280",
            ),
            TrustedCorePolicy.CORE_DEBUG_PACKAGE to setOf(
                "fa9ab6f9a0771e5b29feec3ced42136387995e5f31ef5d75c9a0384259096f88",
            ),
        ),
    )

    override fun createCapability(
        context: ModuleInstanceContext,
        capabilityId: String,
    ): IBinder? {
        if (capabilityId != StandardCapabilities.AppsSort.wireValue) return null
        val reverse = JSONObject(context.normalizedConfigurationJson).getBoolean("reverse")
        return object : IAppSortCapability.Stub() {
            override fun sort(request: AppSortRequestParcel): AppSortResultParcel {
                context.enforceCallingCore()
                if (request.items.size > AppSortRequestParcel.MAX_ITEMS) {
                    return AppSortResultParcel(
                        AppSortResultParcel.STATUS_INVALID_ARGUMENT,
                        emptyList(),
                        "Too many apps",
                    )
                }
                val collator = Collator.getInstance(Locale.forLanguageTag(request.localeTag)).apply {
                    strength = Collator.PRIMARY
                }
                val sorted = request.items.sortedWith { left, right ->
                    val labelOrder = collator.compare(left.label, right.label)
                    if (labelOrder != 0) labelOrder else compareValuesBy(
                        left,
                        right,
                        { it.key.packageName },
                        { it.key.activityName },
                        { it.key.profileSerial },
                    )
                }
                context.checkActive()
                return AppSortResultParcel(
                    AppSortResultParcel.STATUS_SUCCESS,
                    (if (reverse) sorted.reversed() else sorted).map { it.key },
                    null,
                )
            }
        }
    }
}
