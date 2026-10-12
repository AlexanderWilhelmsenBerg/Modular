package io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities;

import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppCatalogInfoParcel;
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppCatalogPageParcel;

/**
 * modular.apps.catalog/v1
 */
interface IAppCatalogCapability {
    AppCatalogInfoParcel getInfo() = 1;

    AppCatalogPageParcel getPage(
        long expectedGeneration,
        int offset,
        int limit
    ) = 2;
}
