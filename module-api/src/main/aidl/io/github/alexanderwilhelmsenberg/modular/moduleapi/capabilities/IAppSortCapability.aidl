package io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities;

import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortRequestParcel;
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortResultParcel;

/**
 * modular.apps.sort/v1
 */
interface IAppSortCapability {
    AppSortResultParcel sort(in AppSortRequestParcel request) = 1;
}
