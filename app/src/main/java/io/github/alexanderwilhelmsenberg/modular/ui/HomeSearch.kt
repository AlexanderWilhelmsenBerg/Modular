package io.github.alexanderwilhelmsenberg.modular.ui

import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import java.text.Collator
import java.util.Locale

/** Accept only a current catalog permutation, and always render current app metadata. */
internal fun homeOrderedApps(
    catalogApps: List<LaunchableApp>,
    orderedApps: List<LaunchableApp>?,
    locale: Locale,
): List<LaunchableApp> {
    if (orderedApps != null && orderedApps.size == catalogApps.size) {
        val currentByKey = catalogApps.associateBy { it.key }
        val orderedKeys = orderedApps.map { it.key }
        if (
            currentByKey.size == catalogApps.size &&
            orderedKeys.toSet() == currentByKey.keys
        ) {
            return orderedKeys.map { currentByKey.getValue(it) }
        }
    }
    val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
    return catalogApps.sortedWith { left, right ->
        val labelOrder = collator.compare(left.label, right.label)
        if (labelOrder != 0) labelOrder else left.key.stableId.compareTo(right.key.stableId)
    }
}

/** Matching is local to Core and preserves the order of the current Home list. */
internal fun filterHomeApps(
    apps: List<LaunchableApp>,
    query: String,
    locale: Locale,
): List<LaunchableApp> {
    val needle = query.trim().lowercase(locale)
    if (needle.isEmpty()) return apps
    // Package identifiers are locale-independent (notably I/i in Turkish).
    val packageNeedle = query.trim().lowercase(Locale.ROOT)
    return apps.filter {
        it.label.lowercase(locale).contains(needle) ||
            it.key.packageName.lowercase(Locale.ROOT).contains(packageNeedle)
    }
}
