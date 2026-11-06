package io.github.alexanderwilhelmsenberg.modular.reference.sort.alphabetical

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

/** Optional entry point explaining where per-instance settings are changed. */
class SortSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(TextView(this@SortSettingsActivity).apply {
                text = getString(R.string.sort_settings_explanation)
                textSize = 18f
            })
        })
    }
}
