package cn.enaium.imcode

import cn.enaium.imcode.app.AppCore
import cn.enaium.imcode.app.Mailbox
import cn.enaium.imcode.config.ConfigStore
import cn.enaium.lsp.edit.EditorFoldRange
import cn.enaium.lsp.model.FoldingRange
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * LSP folding ranges converted for the editor: endCharacter == 0 means the
 * end line is not folded (the range stops at the previous line) and lsp-edit
 * hides startLine+1..endLine, so single-line results are dropped.
 */
class FoldRangeConversionTest {

    private val configFile = File(ConfigStore.filePath)
    private var backup: String? = null
    private val tempDirs = mutableListOf<File>()

    @BeforeTest
    fun snapshotConfig() {
        backup = if (configFile.exists()) configFile.readText() else null
    }

    @AfterTest
    fun restoreConfig() {
        val b = backup
        if (b != null) configFile.writeText(b) else configFile.delete()
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun coreWithDocument(): Pair<AppCore, cn.enaium.imcode.editor.Document> {
        val core = AppCore(Mailbox())
        core.config = core.config.copy(lspServers = emptyList())
        val dir = Files.createTempDirectory("ws").toFile().also { tempDirs += it }
        core.openWorkspaceWindow(dir = dir.absolutePath)
        val file = File(dir, "Folds.kt").apply {
            writeText((0..14).joinToString("\n") { "line$it" } + "\n")
        }
        core.documents.open(file.absolutePath, dir.absolutePath)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !core.documents.isOpen(file.absolutePath)) {
            core.mailbox.drain()
            Thread.sleep(20)
        }
        return core to core.documents.get(file.absolutePath)!!
    }

    @Test
    fun convertsRangesAndAppliesLspEndLineSemantics() {
        val (core, doc) = coreWithDocument()

        core.documents.setFoldRanges(
            doc,
            listOf(
                FoldingRange(startLine = 0, endLine = 3),
                FoldingRange(startLine = 5, endLine = 8, endCharacter = 0),
                FoldingRange(startLine = 9, endLine = 9),
                FoldingRange(startLine = 10, endLine = 12, startCharacter = 4),
            ),
        )

        assertEquals(
            listOf(
                EditorFoldRange(0, 3),
                EditorFoldRange(5, 7),
                EditorFoldRange(10, 12),
            ),
            doc.editor.getFoldRanges(),
        )
    }

    @Test
    fun emptyResultClearsRanges() {
        val (core, doc) = coreWithDocument()
        core.documents.setFoldRanges(doc, listOf(FoldingRange(startLine = 0, endLine = 3)))
        assertEquals(1, doc.editor.getFoldRanges().size)

        core.documents.setFoldRanges(doc, emptyList())

        assertEquals(emptyList(), doc.editor.getFoldRanges())
    }
}
