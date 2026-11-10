package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.os.Bundle
import io.github.alexanderwilhelmsenberg.modular.moduleapi.WorkspacePageContract

class FilesSettingsActivity : FilesActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        if (!intent.hasExtra(WorkspacePageContract.EXTRA_HOST_HANDOFF_NONCE)) {
            intent.putExtra(EXTRA_SETTINGS, true)
        }
        super.onCreate(savedInstanceState)
    }
}
