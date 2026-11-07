package io.github.alexanderwilhelmsenberg.modular.ui

import android.icu.text.AlphabeticIndex
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import java.util.Locale

internal data class HomeSection(val label: String, val apps: List<LaunchableApp>)

/** One ICU index supplies section assignment and the rail's locale alphabet. */
internal class HomeSectionBuilder(private val locale: Locale) {
    private val index = AlphabeticIndex<Any>(locale).addLabels(locale).buildImmutableIndex()
    val alphabet: List<String> = buildList {
        add("#")
        for (i in 0 until index.bucketCount) {
            val bucket = index.getBucket(i)
            if (bucket.label.firstOrNull()?.let { Character.isLetter(it) } == true && bucket.label !in this) {
                add(bucket.label)
            }
        }
    }

    fun bucket(label: String): String {
        if (label.isEmpty() || !Character.isLetter(label.codePointAt(0))) return "#"
        val bucket = index.getBucket(index.getBucketIndex(label))
        return if (bucket.label.firstOrNull()?.let { Character.isLetter(it) } == true) bucket.label else "#"
    }

    fun sections(apps: List<LaunchableApp>): List<HomeSection> {
        val groups = apps.groupBy { bucket(it.label) }
        return alphabet.mapNotNull { label -> groups[label]?.let { HomeSection(label, it) } }
    }
}
