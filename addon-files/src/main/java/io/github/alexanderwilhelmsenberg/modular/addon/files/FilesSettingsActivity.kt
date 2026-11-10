package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.os.Bundle

class FilesSettingsActivity : FilesActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        intent.putExtra(EXTRA_SETTINGS, true)
        super.onCreate(savedInstanceState)
    }
}
