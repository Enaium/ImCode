package cn.enaium.imcode

import cn.enaium.imcode.app.LogLevel
import cn.enaium.imcode.app.OutputLog
import cn.enaium.imcode.config.Config
import cn.enaium.imcode.ui.OutputPanel
import cn.enaium.lsp.edit.EditorFoldRange
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The LSP tab's document: every frame is one summary line with its payload
 * folded under it, so the console opens as a list of frames and what stays on
 * screen while a frame is closed is exactly its first line.
 */
class OutputPanelDocumentTest {

    private fun frame(tag: String, text: String, seq: Long) =
        OutputLog.Entry(tag, LogLevel.LSP, text, seq)

    @Test
    fun aFrameFoldsItsPayload() {
        val panel = OutputPanel { Config() }
        // One entry per frame: summary line, then its payload. The transport
        // writes them together so nothing (a server's stderr, another server)
        // can land between them and break the fold.
        val document = panel.buildLspDocument(
            listOf(
                frame(
                    "kotlin",
                    "[00:11:38] Sending request 'initialize - (1)'.\n" +
                        "{\"jsonrpc\":\"2.0\",\n\"method\":\"initialize\"}\n" +
                        "capabilities=… (payload elided: 900 bytes)",
                    1,
                ),
                frame("kotlin", "[00:11:39] Received response 'initialize - (1)' in 12ms.", 2),
            ),
        )
        assertEquals(
            """
            [kotlin] [00:11:38] Sending request 'initialize - (1)'.
            {"jsonrpc":"2.0",
            "method":"initialize"}
            capabilities=… (payload elided: 900 bytes)
            [kotlin] [00:11:39] Received response 'initialize - (1)' in 12ms.
            """.trimIndent() + "\n",
            document.text,
        )
        // The fold covers everything after the first line and carries it as
        // collapsedText, so a closed frame reads as its summary. Its key is the
        // entry's seq: the console keeps the collapse state against it, because
        // the line numbers move when an old entry slides out of the window.
        assertEquals(
            listOf(
                EditorFoldRange(
                    0,
                    3,
                    collapsedText = "[kotlin] [00:11:38] Sending request 'initialize - (1)'.",
                    key = 1,
                ),
            ),
            document.folds,
        )
    }

    @Test
    fun aFrameWithoutPayloadDoesNotFold() {
        val panel = OutputPanel { Config() }
        val document = panel.buildLspDocument(
            listOf(
                frame("python", "[00:00:01] Sending notification 'textDocument/didOpen'.", 1),
                frame("python", "[00:00:02] Received notification 'window/logMessage'.", 2),
            ),
        )
        assertEquals(
            "[python] [00:00:01] Sending notification 'textDocument/didOpen'.\n" +
                "[python] [00:00:02] Received notification 'window/logMessage'.\n",
            document.text,
        )
        assertEquals(emptyList(), document.folds, "a frame without a payload folds nothing")
    }
}
