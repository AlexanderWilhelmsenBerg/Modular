package io.github.alexanderwilhelmsenberg.modular.reference.sort.alphabetical

import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppKeyParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortItemParcel
import org.junit.Assert.assertEquals
import org.junit.Test

class AlphabeticalSorterTest {
    @Test
    fun sortsLabelsAndUsesStableIdentityTieBreakers() {
        val items = listOf(
            item("z.pkg", "Zulu", 1),
            item("b.pkg", "alpha", 1),
            item("a.pkg", "Alpha", 1),
            item("d.pkg", "Echo", 1),
            item("c.pkg", "Echo", 1),
        )

        val sorted = AlphabeticalSorter.sort(
            items = items,
            localeTag = "en",
        )

        assertEquals(
            listOf(
                "a.pkg",
                "b.pkg",
                "c.pkg",
                "d.pkg",
                "z.pkg",
            ),
            sorted.map { it.key.packageName },
        )
    }

    @Test
    fun preservesDistinctProfileEntries() {
        val sorted = AlphabeticalSorter.sort(
            items = listOf(
                item("same.pkg", "Same", 20),
                item("same.pkg", "Same", 10),
            ),
            localeTag = "en",
        )

        assertEquals(
            listOf(10L, 20L),
            sorted.map { it.key.profileSerial },
        )
    }

    private fun item(
        packageName: String,
        label: String,
        profileSerial: Long,
    ): AppSortItemParcel =
        AppSortItemParcel(
            key = AppKeyParcel(
                packageName = packageName,
                activityName = packageName + ".MainActivity",
                profileSerial = profileSerial,
            ),
            label = label,
        )
}
