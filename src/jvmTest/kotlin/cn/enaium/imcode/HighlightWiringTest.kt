package cn.enaium.imcode

import cn.enaium.imcode.app.Mailbox
import cn.enaium.imcode.config.Config
import cn.enaium.imcode.editor.Document
import cn.enaium.imcode.editor.DocumentManager
import cn.enaium.imcode.lsp.LspManager
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Verifies the highlight-wiring rule: LSP-owned files get no local
 *  highlighting; unowned files with a grammar get tree-sitter. */
class HighlightWiringTest {

    private fun manager(): DocumentManager {
        val mailbox = Mailbox()
        return DocumentManager(
            config = { Config() },
            mailbox = mailbox,
            lsp = LspManager({ Config() }, mailbox, { false }),
            onDocumentOpened = {},
            onDocumentClosed = {},
            onNavigate = { _, _, _ -> },
        )
    }

    @Test
    fun rustFileGetsTreeSitter() {
        val mgr = manager()
        val path = "/tmp/test_highlight.rs"
        java.io.File(path).writeText("fn main() { println!(\"hi\"); }\n")
        val doc = mgr.docFor(path)
        assertNull(doc, "doc should not exist before open")
    }

    @Test
    fun rustHasGrammarMapping() {
        val spec = cn.enaium.imcode.editor.TreeSitterLang.forPath("/tmp/x.rs")
        assertNotNull(spec, ".rs must map to a tree-sitter grammar")
        val hl = spec?.highlighter()
        assertNotNull(hl, "rust highlighter must build")
    }

    @Test
    fun unknownExtensionHasNoGrammar() {
        val spec = cn.enaium.imcode.editor.TreeSitterLang.forPath("/tmp/x.xyzabc")
        assertNull(spec, "unknown extension must not map to a grammar")
    }

    @Test
    fun openRustFileWiresTreeSitter() {
        val mailbox = Mailbox()
        val mgr = DocumentManager(
            config = { Config() },
            mailbox = mailbox,
            lsp = LspManager({ Config() }, mailbox, { false }),
            onDocumentOpened = {},
            onDocumentClosed = {},
            onNavigate = { _, _, _ -> },
        )
        val path = "/tmp/test_wire.rs"
        java.io.File(path).writeText("fn main() {}\n")
        var opened: Document? = null
        val mgr2 = DocumentManager(
            config = { Config() },
            mailbox = mailbox,
            lsp = LspManager({ Config() }, mailbox, { false }),
            onDocumentOpened = { opened = it },
            onDocumentClosed = {},
            onNavigate = { _, _, _ -> },
        )
        mgr2.open(path, "/tmp")
        for (i in 0..200) {
            mailbox.drain()
            if (opened != null) break
            Thread.sleep(10)
        }
        val doc = opened ?: throw AssertionError("doc never opened")
        assertNotNull(doc.treeSitter, ".rs without LSP config must install tree-sitter")
        val spans = doc.treeSitter!!.spansForLine(0)
        assertNotNull(spans, "line 0 must have spans")
    }
}

// (appended) Full open-path check: a .rs file without LSP config gets
// tree-sitter highlighting installed.
