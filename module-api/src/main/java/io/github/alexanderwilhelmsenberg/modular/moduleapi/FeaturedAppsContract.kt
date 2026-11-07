package io.github.alexanderwilhelmsenberg.modular.moduleapi

import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppKeyParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedAppsRequestParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedAppsResultParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedMutationResultParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedParcelBounds
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedPinOrderRequestParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedPinRequestParcel

/**
 * Shared structural checks for modular.apps.featured/v1. Core must also check the
 * active session/instance and that the catalog revision still describes its UI.
 */
object FeaturedAppsContract {
    fun validateRequest(request: FeaturedAppsRequestParcel) {
        validateRequestId(request.requestId)
        require(request.catalogRevision >= 0) { "Invalid catalog revision" }
        require(request.localeTag.length <= FeaturedParcelBounds.MAX_LOCALE_TAG_LENGTH) {
            "Locale tag too long"
        }
        require(request.items.size <= FeaturedParcelBounds.MAX_CATALOG_ITEMS) { "Catalog too large" }
        val keys = HashSet<AppKeyParcel>(request.items.size)
        request.items.forEach { item ->
            validateKey(item.key)
            require(item.label.length <= FeaturedParcelBounds.MAX_LABEL_LENGTH) { "Label too long" }
            require(keys.add(item.key)) { "Duplicate catalog key" }
        }
    }

    fun validateResult(request: FeaturedAppsRequestParcel, result: FeaturedAppsResultParcel) {
        validateRequest(request)
        require(result.requestId == request.requestId) { "Wrong request identity" }
        require(result.catalogRevision == request.catalogRevision) { "Stale catalog revision" }
        require(result.featureRevision >= 0) { "Invalid feature revision" }
        require(result.status in FeaturedAppsResultParcel.STATUS_SUCCESS..FeaturedAppsResultParcel.STATUS_FAILED) {
            "Invalid result status"
        }
        require(result.featureState in FeaturedAppsResultParcel.STATE_READY..FeaturedAppsResultParcel.STATE_NO_HISTORY) {
            "Invalid feature state"
        }
        validateMessage(result.message)
        require(result.pinnedKeys.size <= FeaturedParcelBounds.MAX_PINS) { "Too many pins" }
        require(result.suggestedKeys.size <= FeaturedParcelBounds.MAX_SUGGESTIONS) { "Too many suggestions" }
        val pins = HashSet<AppKeyParcel>()
        result.pinnedKeys.forEach { key ->
            validateKey(key)
            require(pins.add(key)) { "Duplicate pin" }
        }
        val catalog = request.items.mapTo(HashSet()) { it.key }
        val suggestions = HashSet<AppKeyParcel>()
        result.suggestedKeys.forEach { key ->
            validateKey(key)
            require(key in catalog) { "Suggested key absent from catalog" }
            require(key !in pins && suggestions.add(key)) { "Duplicate featured key" }
        }
        if (result.status != FeaturedAppsResultParcel.STATUS_SUCCESS) {
            require(result.pinnedKeys.isEmpty() && result.suggestedKeys.isEmpty()) {
                "Failed result contains featured keys"
            }
        }
    }

    fun validatePinRequest(request: FeaturedPinRequestParcel) {
        validateRequestId(request.requestId)
        require(request.catalogRevision >= 0) { "Invalid catalog revision" }
        require(request.expectedFeatureRevision >= 0) { "Invalid expected feature revision" }
        validateKey(request.key)
    }

    fun validatePinOrderRequest(request: FeaturedPinOrderRequestParcel) {
        validateRequestId(request.requestId)
        require(request.expectedFeatureRevision >= 0) { "Invalid expected feature revision" }
        require(request.orderedKeys.size <= FeaturedParcelBounds.MAX_PINS) { "Too many pins" }
        val keys = HashSet<AppKeyParcel>()
        request.orderedKeys.forEach { key ->
            validateKey(key)
            require(keys.add(key)) { "Duplicate pin in order" }
        }
    }

    fun validateMutationResult(requestId: String, result: FeaturedMutationResultParcel) {
        validateRequestId(requestId)
        require(result.requestId == requestId) { "Wrong mutation identity" }
        require(result.status in FeaturedMutationResultParcel.STATUS_SUCCESS..FeaturedMutationResultParcel.STATUS_FAILED) {
            "Invalid mutation status"
        }
        require(result.featureRevision >= 0) { "Invalid feature revision" }
        validateMessage(result.message)
    }

    fun validatePinOrder(request: FeaturedPinOrderRequestParcel, savedPins: List<AppKeyParcel>) {
        validatePinOrderRequest(request)
        require(savedPins.size == request.orderedKeys.size && savedPins.toSet() == request.orderedKeys.toSet()) {
            "Pin order must be a permutation of saved pins"
        }
    }

    private fun validateRequestId(value: String) {
        require(value.isNotBlank() && value.length <= FeaturedParcelBounds.MAX_REQUEST_ID_LENGTH) {
            "Invalid request identity"
        }
    }

    private fun validateKey(key: AppKeyParcel) {
        require(key.packageName.isNotBlank() &&
            key.packageName.length <= FeaturedParcelBounds.MAX_KEY_PART_LENGTH &&
            key.activityName.isNotBlank() &&
            key.activityName.length <= FeaturedParcelBounds.MAX_KEY_PART_LENGTH) {
            "Invalid app key"
        }
    }

    private fun validateMessage(value: String?) {
        require(value == null || value.length <= FeaturedParcelBounds.MAX_MESSAGE_LENGTH) {
            "Message too long"
        }
    }
}
