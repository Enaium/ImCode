package cn.enaium.imcode

import cn.enaium.imcode.app.Mailbox
import cn.enaium.imcode.config.Config
import cn.enaium.imcode.editor.DocumentManager
import cn.enaium.imcode.lsp.LspManager
import cn.enaium.lsp.edit.PaletteIndex
import cn.enaium.lsp.edit.TokenSpan
import kotlin.test.Test
import kotlin.test.assertEquals

/** Semantic tokens overlay the tree-sitter base layer correctly. */
class MergeSpansTest {

    private fun mgr(): DocumentManager = DocumentManager(
        config = { Config() },
        mailbox = Mailbox(),
        lsp = LspManager({ Config() }, Mailbox(), { false }),
        onDocumentOpened = {},
        onDocumentClosed = {},
        onNavigate = { _, _, _ -> },
    )

    @Test
    fun overlayWinsInsideItsRange() {
        val base = listOf(
            TokenSpan(0, 3, PaletteIndex.KEYWORD),
            TokenSpan(5, 10, PaletteIndex.STRING),
        )
        val overlay = listOf(TokenSpan(1, 2, PaletteIndex.NUMBER))
        val merged = mgr().mergeSpans(base, overlay)
        assertEquals(
            listOf(
                TokenSpan(0, 1, PaletteIndex.KEYWORD),
                TokenSpan(1, 2, PaletteIndex.NUMBER),
                TokenSpan(2, 3, PaletteIndex.KEYWORD),
                TokenSpan(5, 10, PaletteIndex.STRING),
            ),
            merged,
        )
    }

    @Test
    fun emptyOverlayKeepsBase() {
        val base = listOf(TokenSpan(0, 3, PaletteIndex.KEYWORD))
        assertEquals(base, mgr().mergeSpans(base, emptyList()))
    }

    @Test
    fun overlayBeyondBaseAddsSpans() {
        val base = listOf(TokenSpan(0, 3, PaletteIndex.KEYWORD))
        val overlay = listOf(TokenSpan(10, 12, PaletteIndex.STRING))
        val merged = mgr().mergeSpans(base, overlay)
        assertEquals(
            listOf(
                TokenSpan(0, 3, PaletteIndex.KEYWORD),
                TokenSpan(10, 12, PaletteIndex.STRING),
            ),
            merged,
        )
    }
}
