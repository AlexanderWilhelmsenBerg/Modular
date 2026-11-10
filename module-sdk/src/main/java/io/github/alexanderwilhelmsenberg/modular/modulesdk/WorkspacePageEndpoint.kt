package io.github.alexanderwilhelmsenberg.modular.modulesdk

import android.os.ParcelFileDescriptor
import io.github.alexanderwilhelmsenberg.modular.moduleapi.WorkspacePageContract
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IWorkspacePageCapability

/** Authenticated, bounded base for a native-rendered workspace page provider. */
abstract class WorkspacePageEndpoint(private val instance: ModuleInstanceContext) :
    IWorkspacePageCapability.Stub() {
    final override fun getPage(requestJson: String): String {
        instance.enforceCallingCore()
        WorkspacePageContract.validatePageRequest(requestJson)
        return onGetPage(requestJson).also { WorkspacePageContract.validateFilesPage(it) }
    }

    final override fun performAction(requestJson: String): String {
        instance.enforceCallingCore()
        WorkspacePageContract.validateActionRequest(requestJson)
        return onPerformAction(requestJson).also { WorkspacePageContract.validateFilesPage(it) }
    }

    final override fun openDocument(documentId: String, mode: String): ParcelFileDescriptor? {
        instance.enforceCallingCore()
        require(documentId.length in 1..WorkspacePageContract.MAX_DOCUMENT_ID_LENGTH &&
            WorkspacePageContract.validDocumentMode(mode)) { "Invalid document request" }
        return onOpenDocument(documentId, mode)
    }

    protected abstract fun onGetPage(requestJson: String): String
    protected abstract fun onPerformAction(requestJson: String): String
    protected open fun onOpenDocument(documentId: String, mode: String): ParcelFileDescriptor? = null
}
