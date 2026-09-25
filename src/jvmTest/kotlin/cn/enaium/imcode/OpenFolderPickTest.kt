package cn.enaium.imcode

import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImTextureID
import cn.enaium.imgui.ImVec2
import cn.enaium.imcode.app.AppCore
import cn.enaium.imcode.app.Mailbox
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * File -> Open Folder, from the picker's confirm button to the core.
 *
 * ImGuiFileDialog reports a directory selection as the selected file name
 * joined to the current path, so entering a folder after selecting it yields
 * `.../src/src`, which does not exist. The folder then failed the caller's
 * "is this a directory" check and the whole command silently did nothing.
 * This drives the real dialog, clicks its confirm button, and asserts the
 * folder arrives.
 */
class OpenFolderPickTest {

    @Test
    fun confirmDeliversThePickedFolder() {
        val configDir = File.createTempFile("imcode-config", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
        System.setProperty("IMCODE_CONFIG_DIR", configDir.absolutePath)
        var ctx: cn.enaium.imgui.ImGuiContext? = null
        try {
            ctx = ImGui.createContext()
            val io = ImGui.getIO()
            io.displaySize = ImVec2(1280f, 800f)
            io.deltaTime = 1f / 60f
            io.fonts.addFontDefault(ImFontConfig(sizePixels = 13f))
            check(io.fonts.build())
            io.fonts.setTexID(ImTextureID(0uL))

            val core = AppCore(Mailbox())
            fun frame() {
                ImGui.newFrame()
                ImGui.begin("##bg")
                ImGui.end()
                core.fileDialogs.render()
                ImGui.render()
            }

            core.requestOpenFolder()
            frame()
            frame()
            assertTrue(core.fileDialogs.isOpen, "the picker did not open")

            // The dialog positions itself, so its confirm button is found by
            // hovering rather than by assuming a rectangle.
            var y = 420f
            while (y <= 640f && core.fileDialogs.isOpen) {
                var x = 300f
                while (x <= 1000f && core.fileDialogs.isOpen) {
                    io.addMousePosEvent(x, y)
                    io.addMouseButtonEvent(0, false)
                    frame()
                    if (ImGui.isAnyItemHovered()) {
                        io.addMouseButtonEvent(0, true)
                        frame()
                        io.addMouseButtonEvent(0, false)
                        frame()
                    }
                    x += 12f
                }
                y += 12f
            }

            assertTrue(!core.fileDialogs.isOpen, "the picker never closed")
            val pending = core.pendingFolder
            assertTrue(pending != null, "confirming the picker delivered no folder")
            assertTrue(
                File(pending).isDirectory,
                "the delivered folder $pending is not a directory",
            )
        } finally {
            if (ctx != null) ImGui.destroyContext(ctx)
            System.clearProperty("IMCODE_CONFIG_DIR")
            configDir.deleteRecursively()
        }
    }
}
