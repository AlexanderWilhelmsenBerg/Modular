package io.github.alexanderwilhelmsenberg.modular.addon.sort.mostused

import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppKeyParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortItemParcel
import org.junit.Assert.assertEquals
import org.junit.Test

class MostUsedSorterTest {
    @Test
    fun higherForegroundTimeComesFirstEvenWhenLabelWouldSortLater() {
        val items = listOf(item("alpha.pkg", "Alpha"), item("zulu.pkg", "Zulu"))

        assertEquals(
            listOf("zulu.pkg", "alpha.pkg"),
            sorted(items, mapOf("alpha.pkg" to 500L, "zulu.pkg" to 1_000L))
                .map { it.key.packageName },
        )
    }

    @Test
    fun equalWeightsUseLocaleAwareLabelsThenStableIdentity() {
        val items = listOf(
            item("z.pkg", "Zulu"),
            item("b.pkg", "echo"),
            item("a.pkg", "Echo"),
            item("c.pkg", "Alpha"),
        )

        assertEquals(
            listOf("c.pkg", "a.pkg", "b.pkg", "z.pkg"),
            sorted(items, items.associate { it.key.packageName to 100L })
                .map { it.key.packageName },
        )
    }

    @Test
    fun noUsageRecordsProduceAlphabeticalOrder() {
        val items = listOf(item("z.pkg", "Zulu"), item("a.pkg", "Alpha"))

        assertEquals(listOf("a.pkg", "z.pkg"), sorted(items, emptyMap()).map { it.key.packageName })
    }

    @Test
    fun duplicatePackageActivitiesReceiveSamePackageWeightAndProfilesStayDistinct() {
        val ownFirst = item("shared.pkg", "Zeta", activity = "First", profile = 10)
        val ownSecond = item("shared.pkg", "Beta", activity = "Second", profile = 10)
        val otherProfile = item("shared.pkg", "Alpha", activity = "First", profile = 20)
        val unrelated = item("other.pkg", "Gamma", profile = 10)
        val sorted = sorted(
            listOf(otherProfile, unrelated, ownFirst, ownSecond),
            mapOf("shared.pkg" to 1_000L, "other.pkg" to 500L),
        )

        assertEquals(listOf(ownSecond.key, ownFirst.key, unrelated.key, otherProfile.key), sorted.map { it.key })
    }

    @Test
    fun identicalEntriesArePreserved() {
        val duplicate = item("same.pkg", "Same")
        val items = listOf(duplicate, item("other.pkg", "Other"), duplicate)

        assertEquals(items.size, sorted(items, emptyMap()).size)
        assertEquals(2, sorted(items, emptyMap()).count { it.key == duplicate.key })
    }

    @Test
    fun suggestionsExcludePinsUnusedAppsAndOtherProfiles() {
        val pinned = item("pinned.pkg", "Pinned")
        val used = item("used.pkg", "Used")
        val unused = item("unused.pkg", "Unused")
        val other = item("other.pkg", "Other profile", profile = 20)
        val result = MostUsedSorter.suggestions(
            listOf(unused, other, pinned, used), "en", 10,
            mapOf("pinned.pkg" to 2_000, "used.pkg" to 1_000, "other.pkg" to 3_000),
            setOf(pinned.key), 8,
        )
        assertEquals(listOf(used.key), result)
    }

    private fun sorted(
        items: List<AppSortItemParcel>,
        weights: Map<String, Long>,
    ) = MostUsedSorter.sort(items, "en", 10L, weights)

    private fun item(
        packageName: String,
        label: String,
        activity: String = "Main",
        profile: Long = 10L,
    ) = AppSortItemParcel(
        AppKeyParcel(packageName, "$packageName.$activity", profile),
        label,
    )
}
