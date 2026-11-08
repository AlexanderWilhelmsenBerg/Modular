package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.organization.MegaSorterState
import io.github.alexanderwilhelmsenberg.modular.organization.SorterDestination
import io.github.alexanderwilhelmsenberg.modular.organization.SorterRowPhase
import io.github.alexanderwilhelmsenberg.modular.organization.SorterRowState
import io.github.alexanderwilhelmsenberg.modular.organization.SorterSide
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MegaSorterRowTest {
    @get:Rule val rule = createComposeRule()

    @Test fun firstRowsAnimationKeepsSlotsAndSecondRowAcceptsSwipe() {
        val apps = (1..8).map { number ->
            LaunchableApp(AppKey("example.app$number", "Main", 0), "App $number", 0, true, null)
        }
        var rows by mutableStateOf(apps.map(::SorterRowState))
        val assignments = mutableMapOf<AppKey, Int>()
        rule.setContent {
            val sorter = MegaSorterState(rows,
                left = SorterDestination("green", "Green", SorterSide.LEFT),
                right = SorterDestination("blue", "Blue", SorterSide.RIGHT))
            LazyColumn(Modifier.height(220.dp).testTag("sorter-list")) {
                items(rows, key = { it.app.key.stableId }) { row ->
                    MegaSorterRow(row = row, sorter = sorter, onAssign = { side ->
                        assignments[row.app.key] = (assignments[row.app.key] ?: 0) + 1
                        rows = rows.map {
                            if (it.app.key == row.app.key) it.copy(
                                phase = SorterRowPhase.OUTGOING, activeSide = side)
                            else it
                        }
                    }) {
                        Box(Modifier.fillMaxWidth().height(60.dp)) { Text(row.app.label) }
                    }
                }
            }
        }
        val firstTag = "sorter-row:${apps[0].key.stableId}"
        val secondTag = "sorter-row:${apps[1].key.stableId}"
        val beforeFirst = rule.onNodeWithTag(firstTag).getUnclippedBoundsInRoot()
        val beforeSecond = rule.onNodeWithTag(secondTag).getUnclippedBoundsInRoot()
        rule.onNodeWithTag(firstTag).performTouchInput { swipeRight() }
        rule.onNodeWithTag(secondTag).performTouchInput { swipeRight() }
        rule.waitForIdle()
        val afterFirst = rule.onNodeWithTag(firstTag).getUnclippedBoundsInRoot()
        val afterSecond = rule.onNodeWithTag(secondTag).getUnclippedBoundsInRoot()
        assertEquals(beforeFirst.bottom - beforeFirst.top, afterFirst.bottom - afterFirst.top)
        assertEquals(beforeSecond.top - beforeFirst.top, afterSecond.top - afterFirst.top)
        assertEquals(1, assignments[apps[0].key])
        assertEquals(1, assignments[apps[1].key])
        rule.onNodeWithTag("sorter-list").performTouchInput { swipeUp() }
        rule.waitForIdle()
        assertEquals(1, assignments[apps[0].key])
        assertEquals(1, assignments[apps[1].key])
    }
}
