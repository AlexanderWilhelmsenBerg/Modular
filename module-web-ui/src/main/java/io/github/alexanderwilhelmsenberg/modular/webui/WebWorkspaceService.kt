package io.github.alexanderwilhelmsenberg.modular.webui

import android.os.IBinder
import android.view.View
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.modulesdk.BaseModuleService
import io.github.alexanderwilhelmsenberg.modular.modulesdk.ModuleInstanceContext
import io.github.alexanderwilhelmsenberg.modular.modulesdk.WorkspaceSurfaceEndpoint
import io.github.alexanderwilhelmsenberg.modular.modulesdk.WorkspaceSurfaceScope

abstract class WebWorkspaceService : BaseModuleService() {
    protected abstract val descriptorResource: Int
    override val descriptorJson by lazy {
        resources.openRawResource(descriptorResource).bufferedReader().use { it.readText() }
    }

    final override fun createCapability(context: ModuleInstanceContext, capabilityId: String): IBinder? {
        if (capabilityId != StandardCapabilities.WorkspaceSurface.wireValue) return null
        return object : WorkspaceSurfaceEndpoint(this@WebWorkspaceService, context) {
            override fun onCreateView(scope: WorkspaceSurfaceScope): View = page(scope, context.instanceId)
        }
    }

    protected abstract fun page(scope: WorkspaceSurfaceScope, placement: String): View
}
