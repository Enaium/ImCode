package cn.enaium.imcode

import cn.enaium.imcode.app.AppCore
import cn.enaium.imcode.app.Mailbox
import cn.enaium.imcode.config.ConfigStore
import cn.enaium.lsp.edit.EditorPalette
import cn.enaium.lsp.edit.JetBrainsThemes
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Editor themes: the configured `JetBrainsThemes` name must reach every
 * document's palette (on open and when the setting changes); an empty name
 * keeps lsp-edit's built-in Dark palette.
 *
 * AppCore.saveState() persists to the real ~/.imcode/config.json, so the
 * config is snapshotted and restored around these tests.
 */
class EditorThemeTest {

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

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { tempDirs += it }

    private fun coreWith(lspLess: Boolean = true): AppCore {
        val core = AppCore(Mailbox())
        if (lspLess) core.config = core.config.copy(lspServers = emptyList())
        return core
    }

    private fun openDocument(core: AppCore, name: String): String {
        val dir = tempDir("ws")
        core.openWorkspaceWindow(dir = dir.absolutePath)
        val file = File(dir, name).apply { writeText("val x = 1\n") }
        core.documents.open(file.absolutePath, dir.absolutePath)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !core.documents.isOpen(file.absolutePath)) {
            core.mailbox.drain()
            Thread.sleep(20)
        }
        check(core.documents.isOpen(file.absolutePath)) { "document did not open" }
        return file.absolutePath
    }

    private fun paletteOf(core: AppCore, path: String): List<Long> =
        core.documents.get(path)!!.editor.palette.toList()

    @Test
    fun documentOpenedWithConfiguredThemeGetsItsPalette() {
        val core = coreWith()
        val dracula = JetBrainsThemes.all.first { it.first == "Dracula" }.second
        core.config = core.config.copy(editor = core.config.editor.copy(theme = "Dracula"))

        val path = openDocument(core, "A.kt")

        assertEquals(dracula.toList(), paletteOf(core, path))
    }

    @Test
    fun emptyThemeKeepsBuiltInDarkPalette() {
        val core = coreWith()

        val path = openDocument(core, "B.kt")

        assertEquals(EditorPalette.dark.toList(), paletteOf(core, path))
    }

    @Test
    fun applyEditorPrefsSwitchesAndRevertsTheme() {
        val core = coreWith()
        val path = openDocument(core, "C.kt")
        assertEquals(EditorPalette.dark.toList(), paletteOf(core, path))

        val nord = JetBrainsThemes.all.first { it.first == "Nord" }.second
        core.config = core.config.copy(editor = core.config.editor.copy(theme = "Nord"))
        core.documents.applyEditorPrefs()
        assertEquals(nord.toList(), paletteOf(core, path))

        // Back to the default: the built-in palette must be restored, not kept.
        core.config = core.config.copy(editor = core.config.editor.copy(theme = ""))
        core.documents.applyEditorPrefs()
        assertEquals(EditorPalette.dark.toList(), paletteOf(core, path))
    }
}
