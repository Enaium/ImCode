package cn.enaium.imcode

import cn.enaium.imcode.app.Mailbox
import cn.enaium.imcode.config.Config
import cn.enaium.imcode.editor.Document
import cn.enaium.imcode.editor.DocumentManager
import cn.enaium.imcode.lsp.LspManager
import cn.enaium.imcode.util.Uri
import cn.enaium.lsp.edit.DocPos
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the editor-side completion ranking — the exact function the
 * app runs when an LSP completion response lands. Catches regressions like
 * "keyword entries leak into the popup" or "sortText order is ignored"
 * without needing a language server.
 */
class AutocompleteServeTest {

    private fun manager(): DocumentManager = DocumentManager(
        config = { Config() },
        mailbox = Mailbox(),
        lsp = LspManager({ Config() }, Mailbox(), { false }),
        onDocumentOpened = {},
        onDocumentClosed = {},
        onNavigate = { _, _, _ -> },
    )

    private fun doc(text: String = ""): Document {
        val editor = Editor(initialText = text)
        return Document(
            path = "/tmp/auto-test.kt",
            workspaceDir = "/tmp",
            uri = Uri.pathToUri("/tmp/auto-test.kt"),
            languageId = "kotlin",
            editor = editor,
        )
    }

    private fun item(
        label: String,
        kind: Int? = null,
        sortText: String? = null,
        filterText: String? = null,
        insertText: String? = null,
        detail: String? = null,
        textEdit: String? = null,
    ) = CompletionItem(
        label = label,
        kind = kind,
        detail = detail,
        documentation = null,
        textEditText = null,
        insertText = insertText,
        additionalTextEdits = null,
        filterText = filterText,
        sortText = sortText,
        textEdit = textEdit?.let {
            TextEditOrInsert.Edit(TextEdit(Range(Position(0, 0), Position(0, 0)), it))
        },
    )

    private fun ranked(manager: DocumentManager, query: String, items: List<CompletionItem>) =
        manager.rankedItems(query, items)

    @Test
    fun sortsByServerSortTextNotLabelAlphabet() {
        // sortText is the server's intended order and must win over an
        // alphabetical client-side re-sort.
        val items = listOf(
            item("zeta", sortText = "1"),
            item("alpha", sortText = "2"),
            item("middle", sortText = "3"),
        )
        val rows = ranked(manager(), "", items)
        assertEquals(listOf("zeta", "alpha", "middle"), rows.map { it.label })
    }

    @Test
    fun missingSortTextKeepsResponseOrder() {
        // no sortText: the server's response order is preserved (index-padded
        // sort keys), not re-alphabetized.
        val items = listOf(
            item("b"),
            item("a"),
            item("c"),
        )
        val rows = ranked(manager(), "", items)
        assertEquals(listOf("b", "a", "c"), rows.map { it.label })
    }

    @Test
    fun keywordItemsAreExcluded() {
        // keywords must never reach the popup; when EVERYTHING is a keyword
        // the pool falls back to the raw items so the popup is not empty.
        val items = listOf(
            item("class", kind = CompletionItemKind.Keyword),
            item("fun", kind = CompletionItemKind.Keyword),
            item("println", kind = CompletionItemKind.Function),
        )
        val rows = ranked(manager(), "", items)
        assertEquals(listOf("println"), rows.map { it.label })

        val allKeywords = listOf(
            item("class", kind = CompletionItemKind.Keyword),
            item("fun", kind = CompletionItemKind.Keyword),
        )
        assertEquals(listOf("class", "fun"), ranked(manager(), "", allKeywords).map { it.label })
    }

    @Test
    fun prefixFiltersAgainstFilterText() {
        // the query filters on filterText ?: label (case-insensitive) — the
        // regression where typing 'Pa' still showed the whole 'P' list.
        val items = listOf(
            item("Package"),
            item("Pair"),
            item("Process"),
            item("println"),
            item("comparable"),
        )
        val rows = ranked(manager(), "pa", items)
        // mid-word hits survive ("comparable" contains "pa") — the regression
        // guard is that 'Pa' never shows the whole 'P' list: Process/println
        // must be filtered out.
        assertEquals(listOf("Package", "Pair", "comparable"), rows.map { it.label })

        // mid-word hits survive ("comparable" contains "pa")
        val midWord = listOf(item("comparable"), item("print"))
        assertEquals(listOf("comparable"), ranked(manager(), "pa", midWord).map { it.label })
    }

    @Test
    fun overloadsSurviveRankingWithDetails() {
        // kotlin-lsp returns MANY items with the SAME label but different
        // detail (overloads). Every one of them must reach the popup with its
        // own detail — the list must NOT collapse to a single name.
        val items = listOf(
            item("print", detail = "fun print(x: Any?)", sortText = "0"),
            item("print", detail = "fun print(x: Int)", sortText = "0"),
            item("print", detail = "fun print(x: String)", sortText = "0"),
        )
        val rows = ranked(manager(), "p", items)
        assertEquals(3, rows.size, "overloads collapsed: ${rows.map { it.label }}")
        assertTrue(rows.all { it.label == "print" }, "unexpected labels: ${rows.map { it.label }}")
        assertEquals(
            listOf("fun print(x: Any?)", "fun print(x: Int)", "fun print(x: String)"),
            rows.map { it.detail },
        )
        println("OVERLOAD OK -> rows=" + rows.map { "${it.label} (${it.detail})" })
    }

    @Test
    fun invokeCompletionWithNoServerCompletesEmptyAndReleasesGuard() {
        // without an LSP server the request must still complete (empty rows)
        // so the in-flight guard never wedges the popup open.
        val core = manager()
        val doc = doc("fun main() {\n    println(\"hi\")\n}")
        core.invokeCompletion(doc)
        // LspManager.requestCompletion with no matching client calls
        // onResult(emptyList()) synchronously: rows empty, guard released.
        assertFalse(doc.completionInFlight, "no-server completion must release the in-flight guard")
        assertEquals(emptyList(), doc.completionItems)
        assertFalse(doc.completionActive, "empty completion must not activate the popup")
    }

    @Test
    fun acceptCompletionReplacesPrefixAndDeactivates() {
        val core = manager()
        val doc = doc("print(x)")
        doc.editor.setCursor(DocPos(0, 5)) // after "print"
        val rows = core.rankedItems("", listOf(
            item("println", insertText = "println", textEdit = "println()"),
        ))
        assertEquals("println()", rows[0].insert, "insert must prefer textEdit.newText over the label")
        doc.completionItems = rows
        doc.completionActive = true
        core.acceptCompletion(doc, 0)
        assertEquals("println()(x)", doc.editor.getText(), "accept replaces the typed prefix with the insert text")
        assertFalse(doc.completionActive, "accept must close the popup")
        assertEquals(emptyList(), doc.completionItems, "accept must clear the popup rows")
    }

    @Test
    fun acceptCompletionWithoutTextEditFallsBackToLabel() {
        val core = manager()
        val doc = doc("val x = fo")
        doc.editor.setCursor(DocPos(0, 10))
        val rows = core.rankedItems("", listOf(
            item("foo"),
        ))
        assertEquals("foo", rows[0].insert)
        doc.completionItems = rows
        core.acceptCompletion(doc, 0)
        assertEquals("val x = foo", doc.editor.getText())
    }

    @Test
    fun snippetPlaceholdersAreStrippedFromInsert() {
        val core = manager()
        val doc = doc("each")
        doc.editor.setCursor(DocPos(0, 4))
        val rows = core.rankedItems("", listOf(
            item("each", insertText = "each(\${1:arg})"),
        ))
        assertEquals("each()", rows[0].insert, "snippet placeholders must be stripped")
    }
}
