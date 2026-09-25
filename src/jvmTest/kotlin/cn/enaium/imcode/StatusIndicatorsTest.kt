package cn.enaium.imcode

import cn.enaium.imcode.lsp.LspProgress
import cn.enaium.imcode.app.AppCore
import cn.enaium.imcode.app.Mailbox
import cn.enaium.imcode.ui.GotoLineWindow
import cn.enaium.imcode.ui.Notifications
import cn.enaium.imcode.ui.ProgressCenter
import cn.enaium.imcode.ui.StatusBar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The status bar's breadcrumb and the two registries behind the status
 * indicators: progress entries follow what the servers report, and balloons
 * expire unless they are errors.
 */
class StatusIndicatorsTest {

    @Test
    fun breadcrumbIsThePathFromTheRoot() {
        assertEquals(
            listOf("proj", "src", "main", "Foo.kt"),
            StatusBar.breadcrumbSegments("/a/b/proj", "/a/b/proj/src/main/Foo.kt"),
        )
        // A file opened from outside the workspace keeps its whole path, so
        // the bar never claims it lives under a root it does not.
        assertEquals(
            listOf("tmp", "other", "Bar.kt"),
            StatusBar.breadcrumbSegments("/a/b/proj", "/tmp/other/Bar.kt"),
        )
    }

    @Test
    fun gotoLineAcceptsALineOrLineColumn() {
        val lengths = { _: Int -> 40 }
        // 1-based in, 0-based out
        assertEquals(11 to 0, GotoLineWindow.target("12", 100, lengths)?.let { it.line to it.index })
        assertEquals(11 to 4, GotoLineWindow.target("12:5", 100, lengths)?.let { it.line to it.index })
        assertEquals(11 to 4, GotoLineWindow.target("  12 : 5 ", 100, lengths)?.let { it.line to it.index })

        // clamped, so a typo cannot scroll past the end
        assertEquals(99 to 0, GotoLineWindow.target("9999", 100, lengths)?.let { it.line to it.index })
        assertEquals(0 to 0, GotoLineWindow.target("0", 100, lengths)?.let { it.line to it.index })
        assertEquals(0 to 40, GotoLineWindow.target("1:999", 100, lengths)?.let { it.line to it.index })

        // not a position at all
        assertEquals(null, GotoLineWindow.target("", 100, lengths))
        assertEquals(null, GotoLineWindow.target("abc", 100, lengths))
        assertEquals(null, GotoLineWindow.target("12", 0, lengths))
    }

    @Test
    fun progressFollowsWhatTheServersReport() {
        ProgressCenter.clear()
        ProgressCenter.syncLsp(listOf(LspProgress("Indexing", "10 files", 50)))
        val task = ProgressCenter.all().single()
        assertEquals("Indexing", task.title)
        assertEquals(0.5f, task.fraction, "percentage becomes a fraction")

        // A second report updates the same entry instead of adding one.
        ProgressCenter.syncLsp(listOf(LspProgress("Indexing", "20 files", 80)))
        assertEquals(1, ProgressCenter.all().size)
        assertEquals(0.8f, ProgressCenter.all().single().fraction)

        // A job that stops reporting disappears; a percentage-free job is
        // indeterminate rather than stuck at zero.
        ProgressCenter.syncLsp(listOf(LspProgress("Building", null, null)))
        assertEquals(listOf("Building"), ProgressCenter.all().map { it.title })
        assertEquals(null, ProgressCenter.all().single().fraction)

        // The status bar shows the newest job while several run.
        ProgressCenter.syncLsp(
            listOf(LspProgress("Building", null, null), LspProgress("Indexing", null, null)),
        )
        assertEquals(2, ProgressCenter.all().size)
        assertEquals("Indexing", ProgressCenter.latest()?.title, "the newest job is the one shown")

        ProgressCenter.syncLsp(emptyList())
        assertTrue(ProgressCenter.all().isEmpty(), "finished jobs leave the list")
        assertEquals(null, ProgressCenter.latest())
    }

    @Test
    fun hostDialogsOwnTheKeyboard() {
        // The editor reads typed characters from the SDL text events, not from
        // ImGui, so every dialog with a field has to be named here — typing in
        // Go to Line used to land in the code behind it as well.
        val core = AppCore(Mailbox())
        assertTrue(!core.hostFieldFocused, "nothing open: the editor owns the keyboard")
        core.showGotoLine = true
        assertTrue(core.hostFieldFocused, "Go to Line owns the keyboard")
        core.showGotoLine = false
        core.showSearch = true
        assertTrue(core.hostFieldFocused, "Find in Files owns the keyboard")
        core.showSearch = false
        core.showSymbolSearch = true
        assertTrue(core.hostFieldFocused, "Find Symbol owns the keyboard")
        core.showSymbolSearch = false
        core.showSettings = true
        assertTrue(core.hostFieldFocused, "Settings owns the keyboard")
        core.showSettings = false
        assertTrue(!core.hostFieldFocused)
    }

    @Test
    fun balloonsExpireButErrorsWait() {
        Notifications.clear()
        Notifications.info("Indexed", "kotlin-lsp finished")
        Notifications.error("python", "start failed: no such file")
        assertEquals(2, Notifications.pending().size)

        // The same failure twice is one balloon: servers repeat themselves.
        Notifications.error("python", "start failed: no such file")
        assertEquals(2, Notifications.pending().size)

        // A minute of frames ages the info balloon away; the error stays until
        // the user dismisses it.
        repeat(3600) { Notifications.tick(1f / 60f) }
        val left = Notifications.pending()
        assertEquals(1, left.size)
        assertEquals(Notifications.Kind.ERROR, left.single().kind)

        val notice = Notifications.all().first { it.kind == Notifications.Kind.INFO }
        assertNotNull(notice)
        Notifications.clear()
        assertTrue(Notifications.all().isEmpty())
    }
}
