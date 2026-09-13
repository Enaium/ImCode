package cn.enaium.imcode.ui

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiChildFlags
import cn.enaium.imgui.ImGuiInputTextFlags
import cn.enaium.imgui.ImVec2
import cn.enaium.imcode.app.LogLevel
import cn.enaium.imcode.app.OutputLog
import cn.enaium.imcode.config.Config

/**
 * Bottom output panel: "Output" (app/LSP-process logs) + "LSP" RPC frames.
 * The tab row is fixed; the log body is a READ-ONLY multiline input so text
 * can be selected and copied (selection survives scrolling).
 */
class OutputPanel(private val config: () -> Config) {
    private var stickyBottom = BooleanArray(1) { true }
    private var selectedTab = 0
    private var lastRevision = -1
    private var lastTextRevision = -1
    private var logText = ""
    private var lastTextWasLsp = false
    private var logTextWidth = 0f
    private var logLines: List<String> = emptyList()
    private val maxLines = 800

    fun draw() {
        val avail = ImGui.getContentRegionAvail()
        val tabH = ImGui.getFrameHeight()
        val bodyH = (avail.y - tabH - 4f).coerceAtLeast(40f)

        // ---- tab row: fixed (never scrolls with the entries) ----
        if (ImGui.beginChild("##out-tabbar", ImVec2(avail.x, tabH), 0,
                cn.enaium.imgui.ImGuiWindowFlags.NO_SCROLLBAR or cn.enaium.imgui.ImGuiWindowFlags.NO_SCROLL_WITH_MOUSE)) {
            if (ImGui.beginTabBar("##output-tabs", 0)) {
                if (ImGui.beginTabItem("Output")) { selectedTab = 0; ImGui.endTabItem() }
                if (ImGui.beginTabItem("LSP")) { selectedTab = 1; ImGui.endTabItem() }
                ImGui.endTabBar()
            }
        }
        ImGui.endChild()

        // ---- body: selectable log text ----
        if (ImGui.beginChild("##out-body", ImVec2(avail.x, bodyH), ImGuiChildFlags.BORDERS)) {
            drawEntries()
        }
        ImGui.endChild()
    }

    private fun drawEntries() {
        val revision = OutputLog.revision
        if (revision != lastRevision) lastRevision = revision
        val all = OutputLog.snapshot()
        val showLsp = selectedTab == 1
        val entries = if (showLsp) {
            all.filter { it.level == LogLevel.LSP || it.level == LogLevel.SERVER }
        } else {
            all.filter { it.level != LogLevel.LSP && it.level != LogLevel.SERVER }
        }

        ImGui.textDisabled("${entries.size} entries")
        ImGui.sameLine()
        if (ImGui.smallButton("Clear")) OutputLog.clear()
        ImGui.sameLine()
        ImGui.checkbox("Auto-scroll", stickyBottom)
        ImGui.sameLine()
        if (ImGui.smallButton("Copy all")) {
            // ImGui's own clipboard callbacks are not wired up in this
            // backend; talk to the SDL clipboard directly.
            cn.enaium.sdl.SDL.setClipboardText(logText)
            OutputLog.info("Output", "copied ${logText.length} chars to clipboard")
        }
        if (showLsp && !config().rpcLogging) {
            ImGui.sameLine()
            ImGui.textDisabled("(RPC frames disabled - server logs still shown)")
        }
        ImGui.separator()

        // chronological order: OLDEST at top, NEWEST at the bottom (console
        // convention). The body is a scrollable row list; when "Auto-scroll"
        // is on it follows the newest entry.
        val startIndex = if (entries.size > maxLines) entries.size - maxLines else 0
        if (revision != lastTextRevision || showLsp != lastTextWasLsp) {
            val sb = StringBuilder()
            for (i in startIndex..entries.lastIndex) {
                val e = entries[i]
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(e.text)
            }
            logText = sb.toString()
            logLines = entries.subList(startIndex, entries.size).map { it.text }
            lastTextRevision = revision
            lastTextWasLsp = showLsp
            var widest = 0f
            for (line in logLines) {
                widest = maxOf(widest, ImGui.calcTextSize(line).x)
            }
            logTextWidth = widest
        }
        // scrollable body: HORIZONTAL_SCROLLBAR child, rows oldest -> newest
        val bodyW = maxOf(ImGui.getContentRegionAvail().x, logTextWidth + 48f)
        if (ImGui.beginChild(
                "##out-scroll",
                ImVec2(-1f, -1f),
                cn.enaium.imgui.ImGuiChildFlags.BORDERS,
                cn.enaium.imgui.ImGuiWindowFlags.HORIZONTAL_SCROLLBAR,
            )
        ) {
            val nearBottom = ImGui.getScrollY() >= ImGui.getScrollMaxY() - 24f
            for (line in logLines) {
                ImGui.textUnformatted(line)
            }
            if (stickyBottom[0] && (nearBottom || lastTextRevision != revision)) {
                ImGui.setScrollY(ImGui.getScrollMaxY() + 1f)
            }
        }
        ImGui.endChild()
    }
}