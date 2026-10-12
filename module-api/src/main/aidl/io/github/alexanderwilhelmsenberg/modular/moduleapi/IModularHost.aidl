package io.github.alexanderwilhelmsenberg.modular.moduleapi;

import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppCatalogInfoParcel;
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppCatalogPageParcel;
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppKeyParcel;
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppLaunchResultParcel;

/**
 * Per-module-session Binder supplied by Core.
 *
 * Methods on this interface are Core-owned capabilities. A module must not
 * assume that a method is authorized merely because it exists: Core performs
 * session/caller checks and future grant checks at invocation time.
 */
interface IModularHost {
    int getHostProtocolVersion() = 1;
    String[] getCapabilities() = 2;

    AppCatalogInfoParcel getAppCatalogInfo() = 3;
    AppCatalogPageParcel getAppCatalogPage(
        long expectedGeneration,
        int offset,
        int limit
    ) = 4;
    AppLaunchResultParcel launchApp(in AppKeyParcel key) = 5;
}
