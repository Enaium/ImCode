package cn.enaium.imcode

import cn.enaium.imcode.app.Mailbox
import cn.enaium.imcode.config.Config
import cn.enaium.imcode.editor.Document
import cn.enaium.imcode.editor.DocumentManager
import cn.enaium.imcode.lsp.LspManager
import cn.enaium.imcode.util.Uri
import cn.enaium.lsp.edit.Editor
import cn.enaium.lsp.model.CompletionItem
import cn.enaium.lsp.model.CompletionItemKind
import cn.enaium.lsp.model.Documentation
import cn.enaium.lsp.model.Position
import cn.enaium.lsp.model.Range
import cn.enaium.lsp.model.TextEdit
import cn.enaium.lsp.model.TextEditOrInsert
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * kotlin-lsp returns MANY completion items with the SAME label but different
 * detail (overloads). Every one of them must reach the popup with its own
 * detail + documentation — the list must NOT collapse to a single name.
 */
class OverloadCompletionTest {

    private fun item(label: String, detail: String?, doc: String) = CompletionItem(
        label = label,
        kind = CompletionItemKind.Function,
        detail = detail,
        documentation = if (doc.isEmpty()) null else Documentation.StringValue(doc),
        textEditText = null,
        insertText = label,
        additionalTextEdits = null,
        filterText = label,
        sortText = "0",
        textEdit = TextEditOrInsert.Edit(TextEdit(Range(Position(0, 0), Position(0, 3)), label)),
    )

    private fun manager(): DocumentManager = DocumentManager(
        config = { Config() },
        mailbox = Mailbox(),
        lsp = LspManager({ Config() }, Mailbox(), { false }),
        onDocumentOpened = {},
        onDocumentClosed = {},
        onNavigate = { _, _, _ -> },
    )

    @Test
    fun overloadsSurviveRankingWithDetails() {
        val doc = Document(
            path = "/tmp/over.kt", workspaceDir = "/tmp", uri = Uri.pathToUri("/tmp/over.kt"),
            languageId = "kotlin", editor = Editor(),
        )
        val core = manager()
        // three overloads, same label "print", different detail
        val items = listOf(
            item("print", "fun print(x: Any?)", "Prints a value."),
            item("print", "fun print(x: Int)", "Prints an integer."),
            item("print", "fun print(x: String)", "Prints a string."),
        )
        val rows = core.rankedItems("p", items)
        // ALL three overloads reach the popup as separate rows with their own
        // detail + documentation (distinctBy key = label + detail keeps them).
        assertEquals(3, rows.size, "overloads collapsed: ${rows.map { it.label }}")
        assertTrue(rows.all { it.label == "print" }, "unexpected inserts: " + rows.map { it.label })
        assertEquals(
            listOf("fun print(x: Any?)", "fun print(x: Int)", "fun print(x: String)"),
            rows.map { it.detail },
        )
        assertTrue(rows.all { it.doc.isNotEmpty() }, "documentation must reach the popup rows")
        println("OVERLOAD OK -> rows=" + rows.map { "${it.label} (${it.detail}) [${it.doc.take(16)}]" })
    }
}
