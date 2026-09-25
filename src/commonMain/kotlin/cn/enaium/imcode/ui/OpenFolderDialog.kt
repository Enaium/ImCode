package cn.enaium.imcode.ui

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiWindowFlags
import cn.enaium.imgui.ImVec2
import cn.enaium.imcode.app.AppCore

/**
 * Asks where a folder the user picked should open, when the policy is "ask":
 * a new window, the current one, or nothing.
 *
 * Drawn by the hub every frame; it renders only while a folder is waiting.
 */
internal object OpenFolderDialog {

    fun draw(core: AppCore) {
        val dir = core.pendingFolder ?: return
        if (core.pendingFolderOpenOnce) {
            ImGui.openPopup("Open Folder")
            core.pendingFolderOpenOnce = false
        }
        if (ImGui.beginPopupModal("Open Folder", null, ImGuiWindowFlags.ALWAYS_AUTO_RESIZE)) {
            ImGui.textWrapped("Where should '${dir.substringAfterLast('/')}' open?")
            ImGui.textDisabled(dir)
            ImGui.separator()
            if (ImGui.button("New Window", ImVec2(130f, 0f))) {
                core.pendingFolder = null
                ImGui.closeCurrentPopup()
                core.openWorkspaceWindow(dir = dir)
            }
            ImGui.sameLine()
            if (ImGui.button("Current Window", ImVec2(130f, 0f))) {
                core.pendingFolder = null
                ImGui.closeCurrentPopup()
                core.adoptInCurrentWindow(dir)
            }
            ImGui.sameLine()
            if (ImGui.button("Cancel", ImVec2(90f, 0f))) {
                core.pendingFolder = null
                ImGui.closeCurrentPopup()
            }
            ImGui.endPopup()
        }
    }
}
