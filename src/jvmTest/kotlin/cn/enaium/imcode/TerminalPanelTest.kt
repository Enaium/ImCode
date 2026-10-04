package cn.enaium.imcode

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiKey
import cn.enaium.imgui.ImTextureID
import cn.enaium.imgui.ImVec2
import cn.enaium.imcode.ui.TerminalPanel
import cn.enaium.terminal.imgui.TerminalFontSettings
import cn.enaium.terminal.imgui.installTerminalFonts
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bottom console pane: real shells behind ptys, driven the way the app
 * drives them — every session is pumped and drawn each frame, typed text is
 * forwarded from the event loop, and tabs follow the shells' lifetime.
 */
class TerminalPanelTest {

    @Test
    fun `the console runs a shell and shows its output`() {
        withPanel { panel, frame ->
            frame()
            assertTrue(panel.isRunning, "the console should have spawned a shell")

            panel.textInput("echo imcode-console-ok\n")
            val deadline = System.currentTimeMillis() + 15_000
            var screenText = ""
            while (System.currentTimeMillis() < deadline) {
                frame()
                screenText = screen(panel)
                if (screenText.contains("imcode-console-ok")) break
                Thread.sleep(16)
            }
            assertTrue(
                screenText.contains("imcode-console-ok"),
                "the shell's output should reach the grid:\n$screenText",
            )
        }
    }

    @Test
    fun `a tab closes itself when its shell exits`() {
        withPanel { panel, frame ->
            frame()
            assertEquals(1, panel.tabCount, "a fresh pane opens one shell")

            panel.newTab(command = listOf("/bin/sh", "-c", "exit 0"))
            assertEquals(2, panel.tabCount, "the + opens another terminal")

            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline && panel.tabCount > 1) {
                frame()
                Thread.sleep(16)
            }
            assertEquals(1, panel.tabCount, "the exited shell's tab should close itself")
        }
    }

    @Test
    fun `ctrl+c interrupts the foreground process`() {
        withPanel { panel, frame ->
            frame()
            panel.newTab(command = listOf("/bin/sh", "-c", "sleep 30"))
            assertTrue(waitFor(frame) { panel.tabCount == 2 && panel.isRunning }, "the sleep tab should be running")

            // What the SDL backend sends for Ctrl+C: the physical key and the mod flag.
            val io = ImGui.getIO()
            io.addKeyEvent(ImGuiKey.LEFT_CTRL, true)
            io.addKeyEvent(ImGuiKey.MOD_CTRL, true)
            io.addKeyEvent(ImGuiKey.C, true)
            frame()
            io.addKeyEvent(ImGuiKey.C, false)
            io.addKeyEvent(ImGuiKey.LEFT_CTRL, false)
            io.addKeyEvent(ImGuiKey.MOD_CTRL, false)

            assertTrue(waitFor(frame) { panel.tabCount == 1 }, "Ctrl+C should interrupt the shell")
        }
    }

    /** Runs frames until [cond] holds (or the timeout passes). */
    private fun waitFor(frame: () -> Unit, timeoutMs: Long = 15_000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            frame()
            if (cond()) return true
            Thread.sleep(16)
        }
        return cond()
    }

    private fun screen(panel: TerminalPanel): String = buildString {
        val terminal = panel.terminalState ?: return@buildString
        for (row in 0 until terminal.rows) appendLine(terminal.lineAt(row).text(0, terminal.columns))
    }

    /** Runs [block] with a console pane over a temp directory and a headless ImGui context. */
    private fun withPanel(block: (panel: TerminalPanel, frame: () -> Unit) -> Unit) {
        val ctx = ImGui.createContext()
        try {
            val io = ImGui.getIO()
            io.displaySize = ImVec2(900f, 500f)
            io.deltaTime = 1f / 60f
            // Faces go into the atlas before it is built (adding one afterwards
            // trips ImGui's assert).
            val fonts = installTerminalFonts(io.fonts, TerminalFontSettings(regularPath = null, sizePx = 13f))
            check(io.fonts.build())
            io.fonts.setTexID(ImTextureID(0uL))

            val dir = Files.createTempDirectory("imcode-console").toFile()
            val panel = TerminalPanel(dir.absolutePath)
            panel.fonts = fonts
            val frame: () -> Unit = {
                ImGui.newFrame()
                // A fixed size: an auto-sized window would leave the grid a
                // couple of cells wide, and the shell output would not fit.
                ImGui.setNextWindowSize(ImVec2(880f, 460f))
                // The pane has the keyboard: that is what makes the widget
                // accept forwarded text.
                ImGui.setNextWindowFocus()
                ImGui.begin("Terminal##test")
                panel.draw()
                ImGui.end()
                ImGui.render()
            }
            try {
                block(panel, frame)
            } finally {
                panel.close()
                dir.deleteRecursively()
            }
        } finally {
            ImGui.destroyContext(ctx)
        }
    }
}
