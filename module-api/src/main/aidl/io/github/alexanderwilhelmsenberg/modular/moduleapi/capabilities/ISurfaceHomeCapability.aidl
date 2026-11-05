package io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities;

import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.SurfaceActionResultParcel;
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.SurfaceDocumentParcel;

/**
 * modular.surface.home/v1
 *
 * Modules return bounded declarative data. Core validates and renders it.
 */
interface ISurfaceHomeCapability {
    SurfaceDocumentParcel render(String instanceId) = 1;
    SurfaceActionResultParcel performAction(String instanceId, String actionId) = 2;
}
