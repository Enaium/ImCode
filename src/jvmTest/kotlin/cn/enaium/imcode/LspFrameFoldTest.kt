package cn.enaium.imcode

import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImTextureID
import cn.enaium.imgui.ImVec2
import cn.enaium.imcode.app.LogLevel
import cn.enaium.imcode.app.OutputLog
import cn.enaium.imcode.config.Config
import cn.enaium.imcode.ui.OutputPanel
import cn.enaium.imcode.ui.applyFrameFolds
import cn.enaium.lsp.edit.Editor
import cn.enaium.lsp.edit.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The LSP console opens as a list of collapsed frames. The state must survive
 * the document being rebuilt on every new log entry — and an old entry sliding
 * out of the window, which shifts every line number under it.
 */
class LspFrameFoldTest {

    private fun frame(seq: Long, payloadLines: Int): OutputLog.Entry {
        val payload = (1..payloadLines).joinToString("\n") { "{\"line\":$it}" }
        return OutputLog.Entry("kotlin", LogLevel.LSP, "[12:00:00] Received response 'x - ($seq)'.\n$payload", seq)
    }

    private fun states(ed: Editor, panel: OutputPanel, entries: List<OutputLog.Entry>): String {
        val doc = panel.buildLspDocument(entries)
        ed.setText(doc.text)
        ed.setFoldRanges(doc.folds)
        return doc.folds.joinToString(" ") { "${it.key}=${if (ed.isLineFolded(it.startLine)) "folded" else "OPEN"}" }
    }

    @Test
    fun framesStayCollapsedWhileTheLogGrows() {
        val ctx = ImGui.createContext()
        try {
            val io = ImGui.getIO()
            io.displaySize = ImVec2(1280f, 800f)
            io.deltaTime = 1f / 60f
            io.fonts.addFontDefault(ImFontConfig(sizePixels = 13f))
            check(io.fonts.build())
            io.fonts.setTexID(ImTextureID(0uL))
            ImGui.newFrame()
            ImGui.begin("##bg")
            ImGui.end()
            ImGui.render()

            val panel = OutputPanel { Config() }
            val ed = Editor(initialText = "", language = Language(name = "log", keywords = emptySet()))
            val collapsed = HashSet<Long>()
            val expanded = HashSet<Long>()

            fun rebuild(entries: List<OutputLog.Entry>) {
                val doc = panel.buildLspDocument(entries)
                ed.setText(doc.text)
                ed.setFoldRanges(doc.folds)
                applyFrameFolds(ed, doc, collapsed, expanded)
            }

            // Each new entry must not flip the frames already on screen: that
            // is what left half the console unfolded.
            rebuild(listOf(frame(1, 2)))
            assertEquals("1=folded", states(ed, panel, listOf(frame(1, 2))))
            rebuild(listOf(frame(1, 2), frame(2, 3)))
            assertEquals("1=folded 2=folded", states(ed, panel, listOf(frame(1, 2), frame(2, 3))))
            rebuild(listOf(frame(1, 2), frame(2, 3), frame(3, 1)))
            assertEquals(
                "1=folded 2=folded 3=folded",
                states(ed, panel, listOf(frame(1, 2), frame(2, 3), frame(3, 1))),
            )

            // A frame the user expands stays expanded as the log grows.
            val open = panel.buildLspDocument(listOf(frame(1, 2), frame(2, 3), frame(3, 1)))
            ed.toggleFold(open.folds[0].startLine)
            assertTrue(!ed.isLineFolded(open.folds[0].startLine), "the frame did not expand")
            rebuild(listOf(frame(1, 2), frame(2, 3), frame(3, 1), frame(4, 2)))
            assertEquals(
                "1=OPEN 2=folded 3=folded 4=folded",
                states(ed, panel, listOf(frame(1, 2), frame(2, 3), frame(3, 1), frame(4, 2))),
            )

            // The window slides: entry 1 drops off the top and every line
            // number shifts. The expanded frame must stay expanded, the rest
            // collapsed.
            rebuild(listOf(frame(2, 3), frame(3, 1), frame(4, 2), frame(5, 2)))
            assertEquals(
                "2=folded 3=folded 4=folded 5=folded",
                states(ed, panel, listOf(frame(2, 3), frame(3, 1), frame(4, 2), frame(5, 2))),
            )

            // And a frame expanded *after* the slide is still respected.
            val slid = panel.buildLspDocument(listOf(frame(2, 3), frame(3, 1), frame(4, 2), frame(5, 2)))
            ed.toggleFold(slid.folds[2].startLine)
            rebuild(listOf(frame(2, 3), frame(3, 1), frame(4, 2), frame(5, 2), frame(6, 3)))
            assertEquals(
                "2=folded 3=folded 4=OPEN 5=folded 6=folded",
                states(ed, panel, listOf(frame(2, 3), frame(3, 1), frame(4, 2), frame(5, 2), frame(6, 3))),
            )
        } finally {
            ImGui.destroyContext(ctx)
        }
    }
}
