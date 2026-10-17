package io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities;

import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortRequestParcel;
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.SurfaceDocumentParcel;
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.SurfaceActionResultParcel;

/** Core supplies a bounded snapshot ordered by the context's sort provider.
 * The module owns filtering, paging, presentation and opaque action state.
 */
interface ISurfaceDrawerCapability {
    SurfaceDocumentParcel render(String instanceId, String configurationJson, in AppSortRequestParcel apps) = 1;
    SurfaceActionResultParcel performAction(String instanceId, String actionId) = 2;
}
