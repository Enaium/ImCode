package cn.enaium.imcode

import cn.enaium.imcode.app.Mailbox
import cn.enaium.imcode.config.Config
import cn.enaium.imcode.editor.DocumentManager
import cn.enaium.imcode.lsp.LspManager
import cn.enaium.lsp.model.CompletionItem
import cn.enaium.lsp.model.CompletionItemLabelDetails
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Completion popup field mapping: labelDetails.detail (parameter list) and
 * labelDetails.description (source) render inline after the label, while
 * item.detail (e.g. "Unit") goes to the right-aligned trailing slot.
 */
class CompletionRowMappingTest {

    private fun mgr(): DocumentManager = DocumentManager(
        config = { Config() },
        mailbox = Mailbox(),
        lsp = LspManager({ Config() }, Mailbox(), { false }),
        onDocumentOpened = {},
        onDocumentClosed = {},
        onNavigate = { _, _, _ -> },
    )

    @Test
    fun labelDetailsLandInTheirSlots() {
        // The real kotlin-lsp shape (verified against the server): the type
        // lives in labelDetails.description, parameters/source in
        // labelDetails.detail, and item.detail is null.
        val item = CompletionItem(
            label = "PI",
            labelDetails = CompletionItemLabelDetails(
                detail = " (kotlin.math)",
                description = "Double",
            ),
        )

        val row = mgr().rankedItems("", listOf(item)).single()

        assertEquals("PI", row.label)
        assertEquals(" (kotlin.math)", row.detail)
        assertEquals("Double", row.trailing)
    }

    @Test
    fun withoutLabelDetailsAWholeSignatureStaysInline() {
        // Servers that predate labelDetails (and the overload tests) put the
        // full signature in item.detail; it must render inline, not trailing.
        val item = CompletionItem(label = "print", detail = "fun print(x: Any?)")

        val row = mgr().rankedItems("", listOf(item)).single()

        assertEquals("fun print(x: Any?)", row.detail)
        assertEquals("", row.trailing)
    }

    @Test
    fun withoutLabelDetailsABareTypeGoesTrailing() {
        // kotlin-lsp's PI/println style without labelDetails: "Double"/"Unit"
        // is a bare type and must land in the right-aligned slot.
        val item = CompletionItem(label = "PI", detail = "Double")

        val row = mgr().rankedItems("", listOf(item)).single()

        assertEquals("", row.detail)
        assertEquals("Double", row.trailing)
    }
}
