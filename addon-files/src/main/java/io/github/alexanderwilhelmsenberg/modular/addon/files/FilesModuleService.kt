package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.os.IBinder
import android.os.ParcelFileDescriptor
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.modulesdk.BaseModuleService
import io.github.alexanderwilhelmsenberg.modular.modulesdk.ModuleInstanceContext
import io.github.alexanderwilhelmsenberg.modular.modulesdk.WorkspacePageEndpoint

/** Every capability method authenticates the active Core instance before accessing storage. */
class FilesModuleService : BaseModuleService() {
    override val descriptorJson: String by lazy {
        resources.openRawResource(R.raw.module_descriptor).bufferedReader().use { it.readText() }
    }

    private val controller get() = (application as FilesApplication).pageController

    override fun createCapability(context: ModuleInstanceContext, capabilityId: String): IBinder? {
        if (capabilityId == StandardCapabilities.WorkspaceSurface.wireValue) {
            return FilesSurfaceEndpoint(this, context)
        }
        if (capabilityId != StandardCapabilities.WorkspacePage.wireValue) return null
        return object : WorkspacePageEndpoint(context) {
            override fun onGetPage(requestJson: String): String = controller.getPage(requestJson)
            override fun onPerformAction(requestJson: String): String = controller.performAction(requestJson)
            override fun onOpenDocument(documentId: String, mode: String): ParcelFileDescriptor? =
                controller.openDocument(documentId, mode)
        }
    }
}
