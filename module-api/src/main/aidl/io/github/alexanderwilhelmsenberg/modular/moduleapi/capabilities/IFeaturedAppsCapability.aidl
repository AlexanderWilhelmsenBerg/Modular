package io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities;

import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedAppsRequestParcel;
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedAppsResultParcel;
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedPinRequestParcel;
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedPinOrderRequestParcel;
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedMutationResultParcel;

/** modular.apps.featured/v1; acquire through a protocol-v4 instance Binder. */
interface IFeaturedAppsCapability {
    FeaturedAppsResultParcel getFeaturedApps(in FeaturedAppsRequestParcel request) = 1;
    FeaturedMutationResultParcel setPinned(in FeaturedPinRequestParcel request) = 2;
    FeaturedMutationResultParcel replacePinOrder(in FeaturedPinOrderRequestParcel request) = 3;
}
