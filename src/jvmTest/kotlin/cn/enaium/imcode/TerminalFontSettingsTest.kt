package cn.enaium.imcode

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImVec2
import cn.enaium.imcode.app.terminalFontSettings
import cn.enaium.imcode.config.Config
import cn.enaium.imcode.platform.ioFile
import cn.enaium.terminal.core.Terminal
import cn.enaium.terminal.imgui.TerminalRenderer
import cn.enaium.terminal.imgui.installTerminalFonts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Terminal faces.
 *
 * The terminal must never end up with a single face that lacks the glyphs a
 * shell prints (box drawing, CJK, symbols): ImGui draws every missing code
 * point with its `?` glyph, which is what makes a terminal look broken. A
 * fallback is merged in for exactly that.
 */
class TerminalFontSettingsTest {

    @Test
    fun `the platform defaults resolve to existing faces`() {
        val settings = terminalFontSettings(Config(), density = 2f)
        val regular = assertNotNull(settings.regularPath, "a platform monospace face should be found")
        assertTrue(ioFile(regular).isFile, "the resolved face must exist: $regular")
        val fallback = assertNotNull(settings.fallbackPath, "a CJK fallback should be found")
        assertTrue(ioFile(fallback).isFile, "the resolved fallback must exist: $fallback")
    }

    @Test
    fun `a configured face wins and a missing one falls back`() {
        val platform = assertNotNull(terminalFontSettings(Config(), density = 1f).regularPath)
        assertEquals(
            platform,
            terminalFontSettings(Config(terminalFontPath = platform), density = 1f).regularPath,
            "the configured face should be used as-is",
        )

        val missing = terminalFontSettings(
            Config(terminalFontPath = "/nope/missing.ttf", terminalFallbackFontPath = "/nope/missing.ttc"),
            density = 1f,
        )
        assertNotEquals(
            "/nope/missing.ttf",
            missing.regularPath,
            "a path that does not exist must not be baked (ImGui bakes an empty face)",
        )
        assertNotEquals("/nope/missing.ttc", missing.fallbackPath)
    }

    @Test
    fun `the resolved faces cover the glyphs a shell prints`() {
        val context = ImGui.createContext()
        try {
            val io = ImGui.getIO()
            io.displaySize = ImVec2(400f, 200f)
            io.deltaTime = 1f / 60f
            // What a Retina window does: bake for the physical grid, scale back.
            io.fontGlobalScale = 1f / 2f
            val atlas = io.fonts
            val fonts = installTerminalFonts(atlas, terminalFontSettings(Config(), density = 2f))
            check(atlas.build()) { "font atlas build failed" }
            val texture = atlas.getTexDataAsRGBA32()
            println("atlas ${texture.width}x${texture.height} (${texture.width * texture.height * 4 / 1024} KiB)")
            assertTrue(
                texture.width <= 4096 && texture.height <= 4096,
                "the atlas must not overflow, was ${texture.width}x${texture.height}",
            )
            val renderer = TerminalRenderer(fonts)

            // ImGui draws a code point missing from the atlas with the font's
            // fallback glyph (the `?`), so comparing texture coordinates tells
            // the two apart without a screenshot.
            val missingGlyph = textureCoordinates("?", renderer)
            assertTrue(missingGlyph.isNotEmpty(), "the fallback glyph itself is drawn")
            val samples = mapOf(
                "box drawing" to '\u2500',
                "block elements" to '\u2588',
                "arrows" to '\u2192',
                "math" to '\u00b1',
                "geometric shapes" to '\u25cf',
                "symbols" to '\u2605',
                "braille" to '\u28ff',
                "technical" to '\u2318',
                "CJK" to '\u4e2d',
            )
            for ((name, character) in samples) {
                val coordinates = textureCoordinates(character.toString(), renderer)
                assertTrue(coordinates.isNotEmpty(), "$name ('$character') is drawn")
                assertNotEquals(
                    missingGlyph,
                    coordinates,
                    "$name ('$character') must use its own glyph, not the '?' fallback",
                )
            }
        } finally {
            ImGui.destroyContext(context)
        }
    }

    /** The atlas texture coordinates of the single glyph [text] draws with. */
    private fun textureCoordinates(text: String, renderer: TerminalRenderer): Set<Float> {
        val terminal = Terminal(columns = 4, rows = 1)
        terminal.write(text)
        // Two frames: the first lays the window out, the second produces the
        // draw data to inspect.
        repeat(2) {
            ImGui.newFrame()
            ImGui.begin("terminal", null, 0)
            renderer.draw(ImGui.getWindowDrawList(), ImGui.getCursorScreenPos(), terminal, blinkOn = false)
            ImGui.end()
            ImGui.render()
        }
        val data = ImGui.getDrawData()
        val coordinates = LinkedHashSet<Float>()
        for (listIndex in 0 until data.cmdListsCount) {
            val list = data.cmdList(listIndex)
            val vertices = list.copyVtx(0, list.vtxCount)
            for (index in 0 until list.vtxCount * 2) coordinates.add(vertices.uvs[index])
        }
        return coordinates
    }
}
