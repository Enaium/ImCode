package cn.enaium.imcode.ui

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiCol
import cn.enaium.imgui.ImGuiWindowFlags
import cn.enaium.imgui.ImVec2
import cn.enaium.imcode.app.AppCore
import cn.enaium.imcode.platform.ioFile

/**
 * Startup page: recent files (searchable, deletable), the workspace list and
 * the open-directory/open-file entry points. Clicking an entry opens the file.
 */
class WelcomeView {
    private var filter = ""
    private var scrollToTop = true

    fun draw(core: AppCore, menuHeight: Float) {
        val w = ImGui.getIO().displaySize.x
        val h = ImGui.getIO().displaySize.y - menuHeight
        ImGui.setNextWindowPos(ImVec2(0f, menuHeight), cn.enaium.imgui.ImGuiCond.ALWAYS)
        ImGui.setNextWindowSize(ImVec2(w, h), cn.enaium.imgui.ImGuiCond.ALWAYS)
        ImGui.pushStyleColor(ImGuiCol.WINDOW_BG, ImGui.colorConvertU32ToFloat4(0xFF14161A.toInt()))
        if (ImGui.begin(
                "##welcome",
                null,
                ImGuiWindowFlags.NO_TITLE_BAR or ImGuiWindowFlags.NO_RESIZE or ImGuiWindowFlags.NO_MOVE or
                    ImGuiWindowFlags.NO_COLLAPSE or ImGuiWindowFlags.NO_SAVED_SETTINGS or ImGuiWindowFlags.NO_BRING_TO_FRONT_ON_FOCUS,
            )
        ) {
            val cx = w / 2f - 300f
            ImGui.setCursorPos(ImVec2(cx, 70f))
            ImGui.pushStyleColor(ImGuiCol.TEXT, ImGui.colorConvertU32ToFloat4(0xFF4FC1FF.toInt()))
            ImGui.text("ImCode")
            ImGui.popStyleColor()
            ImGui.setCursorPos(ImVec2(cx, 110f))
            ImGui.textDisabled("A minimal LSP-powered text editor (SDL3 + imgui-kmp + lsp-kmp)")
            ImGui.setCursorPos(ImVec2(cx, 150f))

            if (ImGui.beginChild("##welcome-content", ImVec2(600f, h - 220f), 0, ImGuiWindowFlags.NONE)) {
                ImGui.separatorText("Start")
                ImGui.sameLine(0f, 0f)
                if (ImGui.button("Open File...", ImVec2(140f, 0f))) {
                    core.requestOpenFile(null)
                }
                ImGui.sameLine()
                if (ImGui.button("Open Folder...", ImVec2(150f, 0f))) {
                    core.requestOpenFolder()
                }
                ImGui.sameLine()
                if (ImGui.button("Settings", ImVec2(100f, 0f))) {
                    core.showSettings = true
                }

                ImGui.separatorText("Workspaces")
                // the persisted session list (project record page): entries not
                // currently open are opened on click, open ones are focused
                for (entry in core.config.workspaces) {
                    val name = ioFile(entry.dir).name
                    val open = core.workspaces.firstOrNull { it.dir == entry.dir }
                    if (ImGui.selectable("$name##ws-${entry.dir}", open != null, 0, ImVec2(520f, 0f))) {
                        if (open != null) core.focusWorkspace(open) else core.openWorkspaceWindow(entry)
                    }
                    ImGui.sameLine(520f)
                    ImGui.pushStyleColor(ImGuiCol.TEXT, ImGui.colorConvertU32ToFloat4(0xFF7788AA.toInt()))
                    ImGui.textDisabled(entry.dir)
                    ImGui.popStyleColor()
                }
                if (core.config.workspaces.isEmpty()) {
                    ImGui.textDisabled("  No workspaces yet - open a folder to get started.")
                }

                ImGui.separatorText("Recent Files")
                ImGui.setNextItemWidth(600f)
                filter = ImGui.inputTextWithHint("##recent-filter", "Search recent files...", filter) ?: filter
                if (ImGui.isItemDeactivatedAfterEdit()) scrollToTop = true

                if (scrollToTop) {
                    ImGui.setScrollHereY(0f)
                    scrollToTop = false
                }

                val recents = core.config.recentFiles
                    .filter { filter.isBlank() || it.path.contains(filter, ignoreCase = true) }
                    .take(80)
                for (recent in recents) {
                    val name = ioFile(recent.path).name
                    val rowId = "##recent-${recent.path}"
                    if (ImGui.selectable(name + rowId, false, 0, ImVec2(540f, 0f))) {
                        core.openRecent(recent.path)
                    }
                    ImGui.sameLine(540f)
                    ImGui.pushStyleColor(ImGuiCol.TEXT, ImGui.colorConvertU32ToFloat4(0xFF7788AA.toInt()))
                    ImGui.textDisabled(ioFile(recent.path).parent?.absolutePath ?: "")
                    ImGui.popStyleColor()
                    if (ImGui.isItemHovered()) {
                        ImGui.pushStyleColor(ImGuiCol.TEXT, ImGui.colorConvertU32ToFloat4(0xFFFF6666.toInt()))
                        ImGui.text("x  remove from list")
                        ImGui.popStyleColor()
                    }
                    if (ImGui.isItemClicked(1)) { // right click removes
                        core.removeRecent(recent.path)
                    }
                    ImGui.sameLine(0f, 12f)
                    if (ImGui.smallButton("x$rowId")) {
                        core.removeRecent(recent.path)
                    }
                }
                if (recents.isEmpty()) {
                    ImGui.textDisabled("  No recent files.")
                }
            }
            ImGui.endChild()
        }
        ImGui.end()
        ImGui.popStyleColor()
    }
}