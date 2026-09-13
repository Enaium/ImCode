package cn.enaium.imcode

import cn.enaium.imcode.ui.WorkspaceWindow
import kotlin.test.Test
import kotlin.test.assertEquals

/** Tests the tab-close order helpers used by the tab context menu. */
class TabCloseTest {

    private fun ws(tabs: List<String>): WorkspaceWindow {
        val w = WorkspaceWindow("/tmp/ws")
        for (t in tabs) w.activateTab(t)
        return w
    }

    @Test
    fun activateTabAddsInOrder() {
        val w = ws(listOf("a.kt", "b.kt", "c.kt"))
        assertEquals(listOf("a.kt", "b.kt", "c.kt"), w.openTabs.toList())
        assertEquals("c.kt", w.activeFile)
    }

    @Test
    fun removeTabFallsBackToLast() {
        val w = ws(listOf("a.kt", "b.kt", "c.kt"))
        w.activeFile = "b.kt"
        w.removeTab("b.kt")
        assertEquals(listOf("a.kt", "c.kt"), w.openTabs.toList())
        assertEquals("c.kt", w.activeFile)
    }

    // closeTabsExcept/Left/Right need a live AppCore (coreRef) for the
    // unsaved-changes prompt, so they are exercised via the UI, not here.
}
