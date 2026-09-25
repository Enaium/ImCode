package cn.enaium.imcode

import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImTextureID
import cn.enaium.imgui.ImGuiKey
import cn.enaium.imgui.ImVec2
import cn.enaium.imcode.config.Keymap
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeymapPressedTest {
    private fun freshContext(): cn.enaium.imgui.ImGuiContext? {
        val ctx = ImGui.createContext()
        val io = ImGui.getIO()
        io.displaySize = ImVec2(400f, 300f)
        io.deltaTime = 1f / 60f
        io.fonts.addFontDefault(ImFontConfig(sizePixels = 13f))
        check(io.fonts.build()) { "font build failed" }
        io.fonts.setTexID(ImTextureID(0uL))
        return ctx
    }

    @Test
    fun ctrlSMatchesCtrlPlusS() {
        val ctx = freshContext()
        try {
            val io = ImGui.getIO()
            // Key down BEFORE the frame so IsKeyPressed sees it this frame.
            io.addKeyEvent(ImGuiKey.LEFT_CTRL, true)
            io.addKeyEvent(ImGuiKey.S, true)
            ImGui.newFrame()
            val chord = Keymap.parse("Ctrl+S")!!
            assertTrue(Keymap.pressed(chord), "Ctrl+S must be pressed")
            ImGui.render()
        } finally {
            ImGui.destroyContext(ctx)
        }
    }

    @Test
    fun ctrlSDoesNotMatchPlainS() {
        val ctx = freshContext()
        try {
            val io = ImGui.getIO()
            io.addKeyEvent(ImGuiKey.S, true)
            ImGui.newFrame()
            val chord = Keymap.parse("Ctrl+S")!!
            assertFalse(Keymap.pressed(chord), "plain S must not match Ctrl+S")
            ImGui.render()
        } finally {
            ImGui.destroyContext(ctx)
        }
    }
}
