package io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities;

import android.os.ParcelFileDescriptor;

/** Data-only page protocol. The module never sends executable UI code to Core. */
interface IWorkspacePageCapability {
    String getPage(String requestJson);
    String performAction(String requestJson);
    ParcelFileDescriptor openDocument(String documentId, String mode);
}
