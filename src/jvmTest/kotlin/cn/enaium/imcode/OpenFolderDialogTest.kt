package cn.enaium.imcode

import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImTextureID
import cn.enaium.imgui.ImVec2
import cn.enaium.imcode.app.AppCore
import cn.enaium.imcode.app.Mailbox
import cn.enaium.imcode.config.OpenFolderPolicy
import cn.enaium.imcode.ui.OpenFolderDialog
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The "ask" dialog, driven for real: the folder picked in the file dialog is
 * held until the user says where it goes, and each answer does what it says.
 *
 * This is the half of File -> Open Folder that a headless test could not reach
 * before (the dialog lived inside the hub); the file picker itself is covered
 * by the crash log of the original bug and by the app-level `--open` run.
 */
class OpenFolderDialogTest {

    /** True while the dialog is up; only meaningful inside a frame. */
    private var popupOpen = false

    /** Whether a widget was hovered in the last frame (also frame-scoped). */
    private var hovered = false

    private fun withCore(body: (AppCore, (Float, Float, Boolean) -> Unit) -> Unit) {
        val configDir = File.createTempFile("imcode-config", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
        System.setProperty("IMCODE_CONFIG_DIR", configDir.absolutePath)
        val ctx = ImGui.createContext()
        try {
            val io = ImGui.getIO()
            io.displaySize = ImVec2(1280f, 800f)
            io.deltaTime = 1f / 60f
            io.fonts.addFontDefault(ImFontConfig(sizePixels = 13f))
            check(io.fonts.build())
            io.fonts.setTexID(ImTextureID(0uL))
            val core = AppCore(Mailbox())
            body(core) { x, y, click ->
                io.addMousePosEvent(x, y)
                io.addMouseButtonEvent(0, click)
                ImGui.newFrame()
                ImGui.begin("##bg")
                ImGui.end()
                OpenFolderDialog.draw(core)
                // ImGui queries must run inside a frame: IsPopupOpen reads the
                // current window, and there is none between frames.
                popupOpen = ImGui.isPopupOpen("Open Folder")
                hovered = ImGui.isAnyItemHovered()
                ImGui.render()
            }
        } finally {
            ImGui.destroyContext(ctx)
            System.clearProperty("IMCODE_CONFIG_DIR")
            configDir.deleteRecursively()
        }
    }

    /**
     * Clicks the [index]-th button of the dialog's button row (left to right:
     * New Window, Current Window, Cancel), located by hovering. Returns false
     * when no such button exists, so a caller can fail loudly instead of
     * passing without having clicked anything.
     */
    private fun clickButton(frame: (Float, Float, Boolean) -> Unit, index: Int): Boolean {
        val buttons = ArrayList<Float>()
        var inRun = false
        var runStart = 0f
        for (x in 360..780 step 4) {
            frame(x.toFloat(), 136f, false)
            frame(x.toFloat(), 136f, false)
            if (hovered) {
                if (!inRun) {
                    inRun = true
                    runStart = x.toFloat()
                }
            } else if (inRun) {
                inRun = false
                buttons.add((runStart + (x - 4)) / 2f)
            }
        }
        if (inRun) buttons.add((runStart + 780f) / 2f)
        val target = buttons.getOrNull(index) ?: return false
        frame(target, 136f, false)
        frame(target, 136f, true)
        frame(target, 136f, false)
        return true
    }

    @Test
    fun newWindowAnswerOpensTheFolder() = withCore { core, frame ->
        val dir = File.createTempFile("imcode-ws", "").let { f ->
            f.delete()
            f.mkdirs()
            f.absolutePath
        }
        var opened: String? = null
        core.onOpenWorkspaceWindow = { ws -> opened = ws.dir }
        core.config = core.config.copy(openFolderPolicy = OpenFolderPolicy.Ask)

        core.openFolder(dir)
        assertEquals(dir, core.pendingFolder, "the folder waits for an answer")

        frame(640f, 440f, false)
        frame(640f, 440f, false)
        assertTrue(popupOpen, "the ask dialog must be up")
        assertTrue(clickButton(frame, 0), "the New Window button must be there")
        assertEquals(dir, opened, "the New Window answer opens a workspace for it")
        assertNull(core.pendingFolder, "the question is cleared")
        File(dir).deleteRecursively()
    }

    @Test
    fun currentWindowAnswerReusesTheWindow() = withCore { core, frame ->
        val dir = File.createTempFile("imcode-ws", "").let { f ->
            f.delete()
            f.mkdirs()
            f.absolutePath
        }
        var adopted: String? = null
        core.onAdoptWorkspaceInCurrentWindow = { ws ->
            adopted = ws.dir
            true
        }
        core.config = core.config.copy(openFolderPolicy = OpenFolderPolicy.Ask)

        core.openFolder(dir)
        frame(640f, 440f, false)
        assertTrue(clickButton(frame, 1), "the Current Window button must be there")
        assertEquals(dir, adopted, "the Current Window answer reuses the focused window")
        File(dir).deleteRecursively()
    }
}
