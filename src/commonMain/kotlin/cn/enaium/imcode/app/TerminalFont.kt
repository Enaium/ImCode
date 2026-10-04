package cn.enaium.imcode.app

import cn.enaium.imcode.config.Config
import cn.enaium.imcode.platform.Platform
import cn.enaium.imcode.platform.ioFile
import cn.enaium.terminal.imgui.TerminalFontSettings

/**
 * Terminal faces for [cfg].
 *
 * A configured path wins when the file exists; otherwise the platform's
 * monospace face is used, with a CJK fallback merged in. Without a fallback
 * ImGui draws every code point the monospace face lacks — CJK, symbols, box
 * drawing — with its `?` glyph, which is what makes a terminal look broken.
 * ImGui silently bakes an empty face for a path that does not exist, so every
 * candidate is checked here.
 */
internal fun terminalFontSettings(cfg: Config, density: Float): TerminalFontSettings {
    val configuredRegular = cfg.terminalFontPath.ifBlank { null }?.takeIf { ioFile(it).isFile }
    val configuredFallback = cfg.terminalFallbackFontPath.ifBlank { null }?.takeIf { ioFile(it).isFile }
    return TerminalFontSettings(
        regularPath = configuredRegular ?: firstExisting(monospaceCandidates()),
        // The platform faces are only known as a set: a configured face is
        // used alone (bold is overdrawn, italic falls back to the regular).
        italicPath = if (configuredRegular == null) firstExisting(italicCandidates()) else null,
        fallbackPath = configuredFallback ?: firstExisting(fallbackCandidates()),
        // The platform's symbol faces: a CJK fallback alone has no arrows,
        // dingbats or braille, and every code point no merged face provides is
        // drawn with ImGui's `?` glyph.
        extraFallbackPaths = if (configuredFallback == null) extraTerminalFallbacks() else emptyList(),
        sizePx = cfg.fontSize,
        density = density,
    )
}

private fun monospaceCandidates(): List<String> = when {
    Platform.isMac -> listOf(
        "/System/Library/Fonts/SFNSMono.ttf",
        "/System/Library/Fonts/Menlo.ttc",
    )

    Platform.isWindows -> listOf(
        "C:\\Windows\\Fonts\\consola.ttf",
        "C:\\Windows\\Fonts\\lucon.ttf",
    )

    else -> listOf(
        "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf",
        "/usr/share/fonts/opentype/noto/NotoSansMono-Regular.ttf",
    )
}

private fun italicCandidates(): List<String> = when {
    Platform.isMac -> listOf("/System/Library/Fonts/SFNSMonoItalic.ttf")

    Platform.isWindows -> listOf("C:\\Windows\\Fonts\\consolai.ttf")

    else -> listOf("/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Oblique.ttf")
}

private fun fallbackCandidates(): List<String> = when {
    Platform.isMac -> listOf(
        "/System/Library/Fonts/PingFang.ttc",
        "/System/Library/Fonts/Hiragino Sans GB.ttc",
    )

    Platform.isWindows -> listOf("C:\\Windows\\Fonts\\msyh.ttc")

    else -> listOf("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc")
}

/**
 * Extra faces merged into the terminal's regular face after
 * `installTerminalFonts` (which merges one).
 *
 * A CJK face alone carries no arrows, dingbats, braille, box drawing or
 * technical symbols, and ImGui draws every code point none of the merged faces
 * provides with its `?` glyph — which is what a terminal full of box art and
 * icons looks like without these.
 */
internal fun extraTerminalFallbacks(): List<String> = when {
    Platform.isMac -> listOf(
        "/System/Library/Fonts/Apple Symbols.ttf",
        "/System/Library/Fonts/Apple Braille.ttf",
        "/System/Library/Fonts/CJKSymbolsFallback.ttc",
    )

    Platform.isWindows -> listOf(
        "C:\\Windows\\Fonts\\seguisym.ttf",
        "C:\\Windows\\Fonts\\seguiemj.ttf",
    )

    else -> listOf(
        "/usr/share/fonts/truetype/noto/NotoSansSymbols2-Regular.ttf",
        "/usr/share/fonts/truetype/noto/NotoSansSymbols-Regular.ttf",
    )
}.filter { ioFile(it).isFile }

private fun firstExisting(candidates: List<String>): String? = candidates.firstOrNull { ioFile(it).isFile }
