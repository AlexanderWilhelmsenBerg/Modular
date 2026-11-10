package io.github.alexanderwilhelmsenberg.modular.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspacePageScrollTest {
    @Test fun onlyOpaqueBoundedScrollPositionsAreRestored() {
        val value = WorkspacePageScroll("a".repeat(64), 12, 48)
        assertEquals(value, WorkspacePageScroll.parse(value.json().toString()))
        assertNull(WorkspacePageScroll.parse(
            """{"key":"content://private/folder","anchor":12,"offset":48}"""))
        assertNull(WorkspacePageScroll.parse(
            """{"key":"${"a".repeat(64)}","anchor":200,"offset":48}"""))
    }
}
