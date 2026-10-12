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
            val byCollation = collator.compare(
                left.label,
                right.label,
            )

            if (byCollation != 0) {
                return@sortedWith byCollation
            }

            val byExactLabel = left.label.compareTo(
                right.label,
            )

            if (byExactLabel != 0) {
                return@sortedWith byExactLabel
            }

            val byPackage = left.key.packageName.compareTo(
                right.key.packageName,
            )

            if (byPackage != 0) {
                return@sortedWith byPackage
            }

            val byActivity = left.key.activityName.compareTo(
                right.key.activityName,
            )

            if (byActivity != 0) {
                return@sortedWith byActivity
            }

            left.key.profileSerial.compareTo(
                right.key.profileSerial,
            )
        }
    }
}
