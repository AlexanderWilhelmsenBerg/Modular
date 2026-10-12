package io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities;

import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppKeyParcel;
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppLaunchResultParcel;

/**
 * modular.apps.launch/v1
 */
interface IAppLaunchCapability {
    AppLaunchResultParcel launch(in AppKeyParcel key) = 1;
}
