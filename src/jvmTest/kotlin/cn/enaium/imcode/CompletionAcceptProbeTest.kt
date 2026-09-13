package cn.enaium.imcode

import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImVec2
import cn.enaium.imcode.app.Mailbox
import cn.enaium.imcode.config.Config
import cn.enaium.imcode.editor.Document
import cn.enaium.imcode.editor.DocumentManager
import cn.enaium.imcode.lsp.LspManager
import cn.enaium.lsp.edit.DocPos
import cn.enaium.lsp.edit.Editor
import cn.enaium.lsp.model.CompletionItem
import kotlin.test.Test
import kotlin.test.assertEquals

/** Accepting a completion must replace the typed prefix with the insert text. */
class CompletionAcceptProbeTest {

    private fun mgr(): DocumentManager = DocumentManager(
        config = { Config() },
        mailbox = Mailbox(),
        lsp = LspManager({ Config() }, Mailbox(), { false }),
        onDocumentOpened = {},
        onDocumentClosed = {},
        onNavigate = { _, _, _ -> },
    )

    @Test
    fun acceptInsertsCompletionText() {
        val ctx = ImGui.createContext()
        try {
            val io = ImGui.getIO()
            io.displaySize = ImVec2(800f, 600f)
            io.deltaTime = 1f / 60f
            io.fonts.addFontDefault(ImFontConfig(sizePixels = 13f))
            check(io.fonts.build()) { "font build failed" }
            io.fonts.setTexID(0)

            val m = mgr()
            val editor = Editor(initialText = "p", language = cn.enaium.lsp.edit.Language.kotlin)
            editor.setCursor(DocPos(0, 1))
            val doc = Document(
                path = "/tmp/Accept.kt",
                workspaceDir = "/tmp",
                uri = "file:///tmp/Accept.kt",
                languageId = "kotlin",
                editor = editor,
            )
            m.registerDocForTest(doc)
            doc.completionItems = m.rankedItems("p", listOf(CompletionItem(label = "println")))
            doc.completionActive = true
            println("PROBE rows=" + doc.completionItems.map { it.label + "/" + it.insert })
            m.acceptCompletion(doc, 0)
            println("PROBE text='" + editor.getText() + "'")
            assertEquals("println", editor.getText())
        } finally {
            ImGui.destroyContext(ctx)
        }
    }

    @Test
    fun acceptAppliesAdditionalTextEdits() {
        val ctx = ImGui.createContext()
        try {
            val io = ImGui.getIO()
            io.displaySize = ImVec2(800f, 600f)
            io.deltaTime = 1f / 60f
            io.fonts.addFontDefault(ImFontConfig(sizePixels = 13f))
            check(io.fonts.build()) { "font build failed" }
            io.fonts.setTexID(0)

            val m = mgr()
            val editor = Editor(initialText = "PI", language = cn.enaium.lsp.edit.Language.kotlin)
            editor.setCursor(DocPos(0, 2))
            val doc = Document(
                path = "/tmp/AcceptImport.kt",
                workspaceDir = "/tmp",
                uri = "file:///tmp/AcceptImport.kt",
                languageId = "kotlin",
                editor = editor,
            )
            m.registerDocForTest(doc)
            // Auto-import style item: completion text + an additional edit
            // inserting the import at the top of the file.
            val item = cn.enaium.lsp.model.CompletionItem(
                label = "PI",
                additionalTextEdits = listOf(
                    cn.enaium.lsp.model.TextEdit(
                        range = cn.enaium.lsp.model.Range(
                            cn.enaium.lsp.model.Position(0, 0),
                            cn.enaium.lsp.model.Position(0, 0),
                        ),
                        newText = "import kotlin.math.PI\n",
                    ),
                ),
            )
            doc.completionItems = m.rankedItems("PI", listOf(item))
            doc.completionActive = true
            m.acceptCompletion(doc, 0)
            val text = editor.getText()
            println("PROBE import text='" + text.replace("\n", "\\n") + "'")
            assertEquals(true, text.contains("import kotlin.math.PI"), "import edit must be applied")
            assertEquals(true, text.contains("PI"), "completion text must be applied")
            // Caret ends after the inserted completion text.
            assertEquals(DocPos(1, 2), editor.cursor)
        } finally {
            ImGui.destroyContext(ctx)
        }
    }

    @Test
    fun acceptingCompletionWithAutoImportIsOneUndoStep() {
        val ctx = ImGui.createContext()
        try {
            val io = ImGui.getIO()
            io.displaySize = ImVec2(800f, 600f)
            io.deltaTime = 1f / 60f
            io.fonts.addFontDefault(ImFontConfig(sizePixels = 13f))
            check(io.fonts.build()) { "font build failed" }
            io.fonts.setTexID(0)

            val m = mgr()
            val editor = Editor(initialText = "PI", language = cn.enaium.lsp.edit.Language.kotlin)
            editor.setCursor(DocPos(0, 2))
            val doc = Document(
                path = "/tmp/AcceptUndo.kt",
                workspaceDir = "/tmp",
                uri = "file:///tmp/AcceptUndo.kt",
                languageId = "kotlin",
                editor = editor,
            )
            m.registerDocForTest(doc)
            val item = cn.enaium.lsp.model.CompletionItem(
                label = "PI",
                insertText = "kotlin.math.PI",
                additionalTextEdits = listOf(
                    cn.enaium.lsp.model.TextEdit(
                        range = cn.enaium.lsp.model.Range(
                            cn.enaium.lsp.model.Position(0, 0),
                            cn.enaium.lsp.model.Position(0, 0),
                        ),
                        newText = "import kotlin.math.PI\n",
                    ),
                ),
            )
            doc.completionItems = m.rankedItems("PI", listOf(item))
            doc.completionActive = true

            m.acceptCompletion(doc, 0)
            assertEquals("import kotlin.math.PI\nkotlin.math.PI", editor.getText())

            // The inserted text and its auto-import are one undo step: a
            // single undo restores the typed prefix and drops the import.
            editor.undo()

            assertEquals("PI", editor.getText())
        } finally {
            ImGui.destroyContext(ctx)
        }
    }
}
