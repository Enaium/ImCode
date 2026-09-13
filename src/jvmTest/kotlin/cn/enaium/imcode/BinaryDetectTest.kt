package cn.enaium.imcode

import cn.enaium.imcode.app.Mailbox
import cn.enaium.imcode.config.Config
import cn.enaium.imcode.editor.DocumentManager
import cn.enaium.imcode.lsp.LspManager
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Tests for the text/binary detection used when opening files. */
class BinaryDetectTest {

    private fun detect(bytes: ByteArray): Boolean {
        val mgr = DocumentManager(
            config = { Config() },
            mailbox = Mailbox(),
            lsp = LspManager({ Config() }, Mailbox(), { false }),
            onDocumentOpened = {},
            onDocumentClosed = {},
            onNavigate = { _, _, _ -> },
        )
        return mgr.isBinaryContent(bytes)
    }

    @Test
    fun plainTextIsNotBinary() {
        assertFalse(detect("hello world\nsecond line\n".toByteArray()))
    }

    @Test
    fun utf8TextIsNotBinary() {
        assertFalse(detect("// 中文注释\nfun main() {}\n".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun nulByteIsBinary() {
        assertTrue(detect(byteArrayOf(1, 2, 3, 0, 5, 6, 7)))
    }

    @Test
    fun manyControlBytesAreBinary() {
        val bytes = ByteArray(100) { if (it % 2 == 0) 3 else 65 } // 50% control bytes
        assertTrue(detect(bytes))
    }

    @Test
    fun fewControlBytesAreStillText() {
        // One control byte among normal text is tolerated.
        val bytes = "line one\nline two\nline three\n".toByteArray()
        assertFalse(detect(bytes))
    }
}
