package io.github.alexanderwilhelmsenberg.modular.addon.sort.mostused

import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortItemParcel
import java.text.Collator
import java.util.Locale

/** Pure ordering logic; every supplied entry remains in the result. */
object MostUsedSorter {
    fun sort(
        items: List<AppSortItemParcel>,
        localeTag: String,
        ownProfileSerial: Long,
        foregroundMillisByPackage: Map<String, Long>,
    ): List<AppSortItemParcel> {
        val collator = Collator.getInstance(Locale.forLanguageTag(localeTag)).apply {
            strength = Collator.PRIMARY
        }

        fun weight(item: AppSortItemParcel): Long =
            if (item.key.profileSerial == ownProfileSerial) {
                foregroundMillisByPackage[item.key.packageName]?.coerceAtLeast(0L) ?: 0L
            } else {
                0L
            }

        return items.sortedWith { left, right ->
            val byUsage = weight(right).compareTo(weight(left))
            if (byUsage != 0) return@sortedWith byUsage

            val byLabel = collator.compare(left.label, right.label)
            if (byLabel != 0) return@sortedWith byLabel

            val byExactLabel = left.label.compareTo(right.label)
            if (byExactLabel != 0) return@sortedWith byExactLabel

            val byPackage = left.key.packageName.compareTo(right.key.packageName)
            if (byPackage != 0) return@sortedWith byPackage

            val byActivity = left.key.activityName.compareTo(right.key.activityName)
            if (byActivity != 0) return@sortedWith byActivity

            left.key.profileSerial.compareTo(right.key.profileSerial)
        }
    }
}
