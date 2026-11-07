package io.github.alexanderwilhelmsenberg.modular.modulesdk

import io.github.alexanderwilhelmsenberg.modular.moduleapi.FeaturedAppsContract
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IFeaturedAppsCapability
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppKeyParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedAppsRequestParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedAppsResultParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedMutationResultParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedPinOrderRequestParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedPinRequestParcel

/**
 * Optional authenticated base Stub for an instance-scoped featured provider.
 * Implementations still own durable pin storage, expected-revision checks, and
 * the unique-permutation check against their actual saved pins.
 */
abstract class FeaturedAppsEndpoint(
    private val context: ModuleInstanceContext,
) : IFeaturedAppsCapability.Stub() {
    private val catalogLock = Any()
    private var latestCatalogRevision = -1L
    private var latestCatalogKeys = emptySet<AppKeyParcel>()
    private var latestReadToken = 0L

    final override fun getFeaturedApps(request: FeaturedAppsRequestParcel): FeaturedAppsResultParcel {
        context.enforceCallingCore()
        FeaturedAppsContract.validateRequest(request)
        val readToken = synchronized(catalogLock) {
            latestReadToken += 1
            latestCatalogRevision = -1L
            latestCatalogKeys = emptySet()
            latestReadToken
        }
        val result = onGetFeaturedApps(request)
        FeaturedAppsContract.validateResult(request, result)
        if (result.status == FeaturedAppsResultParcel.STATUS_SUCCESS) {
            synchronized(catalogLock) {
                if (readToken == latestReadToken) {
                    latestCatalogRevision = request.catalogRevision
                    latestCatalogKeys = request.items.mapTo(HashSet()) { it.key }
                }
            }
        }
        return result
    }

    final override fun setPinned(request: FeaturedPinRequestParcel): FeaturedMutationResultParcel {
        context.enforceCallingCore()
        FeaturedAppsContract.validatePinRequest(request)
        if (request.pinned) {
            synchronized(catalogLock) {
                require(request.catalogRevision == latestCatalogRevision &&
                    request.key in latestCatalogKeys) {
                    "Pin key is absent from the current read catalog"
                }
            }
        }
        return onSetPinned(request).also {
            FeaturedAppsContract.validateMutationResult(request.requestId, it)
        }
    }

    final override fun replacePinOrder(
        request: FeaturedPinOrderRequestParcel,
    ): FeaturedMutationResultParcel {
        context.enforceCallingCore()
        FeaturedAppsContract.validatePinOrderRequest(request)
        return onReplacePinOrder(request).also {
            FeaturedAppsContract.validateMutationResult(request.requestId, it)
        }
    }

    protected abstract fun onGetFeaturedApps(request: FeaturedAppsRequestParcel): FeaturedAppsResultParcel
    protected abstract fun onSetPinned(request: FeaturedPinRequestParcel): FeaturedMutationResultParcel
    protected abstract fun onReplacePinOrder(
        request: FeaturedPinOrderRequestParcel,
    ): FeaturedMutationResultParcel
}
