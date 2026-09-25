package cn.enaium.imcode

import cn.enaium.imcode.app.AppCore
import cn.enaium.imcode.app.Mailbox
import cn.enaium.imcode.config.ConfigStore
import cn.enaium.imcode.config.OpenFolderPolicy
import cn.enaium.imcode.ui.WorkspaceWindow
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Opening a folder: the policy decides where it goes (a new window, the
 * focused one, or a question), and every folder opened that way is remembered
 * — the history the welcome screen and the File menu read.
 *
 * The config directory is redirected to a temp folder, so the test never
 * touches the user's `~/.imcode/config.json`.
 */
class OpenFolderPolicyTest {

    private fun withCore(body: (AppCore, String) -> Unit) {
        val dir = File.createTempFile("imcode-config", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
        System.setProperty("IMCODE_CONFIG_DIR", dir.absolutePath)
        try {
            body(AppCore(Mailbox()), dir.absolutePath)
        } finally {
            System.clearProperty("IMCODE_CONFIG_DIR")
            dir.deleteRecursively()
        }
    }

    private fun tempFolder(name: String): String =
        File.createTempFile(name, "").let { f ->
            f.delete()
            f.mkdirs()
            f.absolutePath
        }

    @Test
    fun eachPolicyRoutesTheFolderAndRecentsRememberIt() = withCore { core, configDir ->
        assertEquals(configDir, File(ConfigStore.dirPath).absolutePath, "the test owns the config dir")

        var openedInNewWindow: String? = null
        var adopted: String? = null
        core.onOpenWorkspaceWindow = { ws -> openedInNewWindow = ws.dir }
        core.onAdoptWorkspaceInCurrentWindow = { ws ->
            adopted = ws.dir
            true
        }

        val first = tempFolder("imcode-ws-new")
        core.config = core.config.copy(openFolderPolicy = OpenFolderPolicy.NewWindow)
        core.openFolder(first)
        assertEquals(first, openedInNewWindow, "the new-window policy opens a window")
        assertNull(adopted)
        assertEquals(first, core.config.recentFolders.first().path, "the folder is remembered")
        assertTrue(File(ConfigStore.filePath).exists(), "the history is persisted")

        val second = tempFolder("imcode-ws-current")
        core.config = core.config.copy(openFolderPolicy = OpenFolderPolicy.CurrentWindow)
        core.openFolder(second)
        assertEquals(second, adopted, "the current-window policy reuses the focused window")
        assertEquals(second, core.config.recentFolders.first().path, "newest first")
        assertEquals(listOf(second, first), core.config.recentFolders.map { it.path })

        val third = tempFolder("imcode-ws-ask")
        core.config = core.config.copy(openFolderPolicy = OpenFolderPolicy.Ask)
        core.openFolder(third)
        assertEquals(third, core.pendingFolder, "the ask policy waits for the answer")
        assertTrue(core.pendingFolderOpenOnce, "and asks once")
        // Answering the question clears it (the modal's buttons do this).
        core.pendingFolder = null

        // Opening a folder that is already open just focuses it.
        val ws = WorkspaceWindow(first)
        core.workspaces.add(ws)
        core.focusWorkspace(ws)
        core.openFolder(first)
        assertNull(core.pendingFolder, "an open folder is focused, not asked about again")

        for (dir in listOf(first, second, third)) File(dir).deleteRecursively()
    }
}
