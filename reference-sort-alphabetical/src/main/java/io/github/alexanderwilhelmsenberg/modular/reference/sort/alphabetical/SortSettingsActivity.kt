package io.github.alexanderwilhelmsenberg.modular.reference.sort.alphabetical

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Switch

/** Optional setting owned by the independently installed reference APK. */
class SortSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val preferences = getSharedPreferences(
            AlphabeticalSortService.SORT_PREFERENCES,
            MODE_PRIVATE,
        )
        val reverse = Switch(this).apply {
            text = getString(R.string.reverse_order)
            isChecked = preferences.getBoolean(
                AlphabeticalSortService.REVERSE_ORDER,
                false,
            )
            setOnCheckedChangeListener { _, checked ->
                preferences.edit()
                    .putBoolean(AlphabeticalSortService.REVERSE_ORDER, checked)
                    .apply()
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(reverse)
        })
    }
}
