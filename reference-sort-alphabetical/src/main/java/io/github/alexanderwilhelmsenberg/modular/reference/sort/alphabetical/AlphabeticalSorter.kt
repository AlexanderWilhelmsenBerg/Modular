package io.github.alexanderwilhelmsenberg.modular.reference.sort.alphabetical

import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortItemParcel
import java.text.Collator
import java.util.Locale

object AlphabeticalSorter {
    fun sort(
        items: List<AppSortItemParcel>,
        localeTag: String,
    ): List<AppSortItemParcel> {
        val locale = runCatching {
            Locale.forLanguageTag(localeTag)
        }.getOrDefault(Locale.ROOT)

        val collator = Collator.getInstance(locale).apply {
            strength = Collator.PRIMARY
        }

        return items.sortedWith { left, right ->
            val byLabel = collator.compare(left.label, right.label)

            when {
                byLabel != 0 -> byLabel
                left.label != right.label ->
                    left.label.compareTo(right.label, ignoreCase = true)
                left.key.packageName != right.key.packageName ->
                    left.key.packageName.compareTo(right.key.packageName)
                left.key.activityName != right.key.activityName ->
                    left.key.activityName.compareTo(right.key.activityName)
                else ->
                    left.key.profileSerial.compareTo(right.key.profileSerial)
            }
        }
    }
}
