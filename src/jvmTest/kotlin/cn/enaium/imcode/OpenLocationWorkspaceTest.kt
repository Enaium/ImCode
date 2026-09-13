package cn.enaium.imcode

import cn.enaium.imcode.app.AppCore
import cn.enaium.imcode.app.Mailbox
import cn.enaium.imcode.config.ConfigStore
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Go-to-definition targets (and structure/symbol-search jumps) can live
 * outside every workspace root — dependency sources, generated code. Those
 * must open as a tab in the active workspace; spawning a new project window
 * for them is wrong.
 *
 * AppCore.saveState() persists to the real ~/.imcode/config.json, so these
 * tests snapshot and restore it — otherwise their temp workspaces leak into
 * the user's project list and reopen as blank windows on the next start.
 */
class OpenLocationWorkspaceTest {

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

    private fun core(): AppCore {
        val core = AppCore(Mailbox())
        core.config = core.config.copy(lspServers = emptyList())
        return core
    }

    @Test
    fun jumpOutsideWorkspaceOpensTabInsteadOfNewProject() {
        val core = core()
        val wsDir = tempDir("ws")
        val ws = core.openWorkspaceWindow(dir = wsDir.absolutePath)
        val workspacesAfterOpen = core.workspaces.size

        val outside = File(tempDir("outside"), "Outside.kt").apply { writeText("val x = 1\n") }

        core.openLocation(outside.absolutePath, 0, 0)

        assertEquals(workspacesAfterOpen, core.workspaces.size) // no new project window
        assertTrue(outside.absolutePath in ws.openTabs.toList()) // opened as a tab
    }

    @Test
    fun jumpInsideWorkspaceStillUsesThatWorkspace() {
        val core = core()
        val wsDir = tempDir("ws")
        val ws = core.openWorkspaceWindow(dir = wsDir.absolutePath)
        val workspacesAfterOpen = core.workspaces.size

        val inside = File(wsDir, "Inside.kt").apply { writeText("val y = 2\n") }

        core.openLocation(inside.absolutePath, 0, 0)

        assertEquals(workspacesAfterOpen, core.workspaces.size)
        assertTrue(inside.absolutePath in ws.openTabs.toList())
    }
}
