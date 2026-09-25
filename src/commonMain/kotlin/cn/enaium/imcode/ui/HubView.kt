package cn.enaium.imcode.ui

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiChildFlags
import cn.enaium.imgui.ImGuiWindowFlags
import cn.enaium.imgui.ImVec2
import cn.enaium.imcode.app.AppCore
import cn.enaium.imcode.app.WindowCtx

/**
 * The auxiliary UI of the primary window: welcome screen (hub mode only),
 * settings, global search, about box, the exit-confirm modal, file dialogs
 * and the status bar. In workspace mode only the floating aux windows are
 * drawn (above the workspace layout); the welcome stays for hub mode.
 */
class HubView(
    private val core: AppCore,
    private val ctx: WindowCtx,
) {
    private val welcome = WelcomeView()
    private val settings = SettingsWindow()
    internal val searchWindow = SearchWindow(core.searchService, core.mailbox)

    /** Hub mode: menu + fullscreen welcome + aux UI + status bar. */
    fun drawPrimary() {
        drawMenuBar(core, null)
        val menuH = ImGui.getFrameHeight()
        if (core.showWelcome) welcome.draw(core, menuH)
        drawAux()
        StatusBar.draw(core, null)
    }

    /** Workspace mode: only the floating aux windows (settings/search/about/...). */
    fun drawAux() {
        // Floating UI belongs to the window that asked for it: drawn here, in
        // every window, it would otherwise appear once per project (and with
        // the previous primary-only rule, on top of the wrong project).
        if (!core.ownsFloatingUi(ctx.windowId)) return
        if (core.searchScopeDir != null) {
            searchWindow.show(core.searchScopeDir)
            core.searchScopeDir = null
        }
        settings.draw(core)
        searchWindow.draw(core)
        if (core.showSymbolSearch) {
            core.symbolSearchWindow.draw(core)
        }
        if (core.showAbout) drawAbout()
        if (core.showNotifications) NotificationView.drawWindow(core)
        if (core.showGotoLine) GotoLineWindow.draw(core, ctx.workspace)
        ProgressWindow.draw(core)
        drawConfirmModal()
        core.fileDialogs.render()
        OpenFolderDialog.draw(core)
    }

    private fun drawConfirmModal() {
        val text = core.confirmText ?: return
        if (core.confirmOpenOnce) {
            ImGui.openPopup("Confirm")
            core.confirmOpenOnce = false
        }
        if (ImGui.beginPopupModal("Confirm", null, ImGuiWindowFlags.ALWAYS_AUTO_RESIZE)) {
            ImGui.textWrapped(text)
            ImGui.separator()
            if (ImGui.button("OK", ImVec2(90f, 0f))) {
                val action = core.confirmAction
                core.confirmText = null
                core.confirmAction = null
                ImGui.closeCurrentPopup()
                action?.invoke()
            }
            ImGui.sameLine()
            if (ImGui.button("Cancel", ImVec2(90f, 0f))) {
                core.confirmText = null
                core.confirmAction = null
                ImGui.closeCurrentPopup()
            }
            ImGui.endPopup()
        }
    }

    internal fun drawAbout() {
        val openArr = BooleanArray(1) { core.showAbout }
        if (ImGui.begin("About ImCode", openArr, ImGuiWindowFlags.ALWAYS_AUTO_RESIZE)) {
            if (!openArr[0]) core.showAbout = false
            ImGui.text("ImCode")
            ImGui.textDisabled("A minimal LSP-powered text editor (SDL3 + imgui-kmp + lsp-kmp)")
            ImGui.text("Font size: %.1f".format(core.config.fontSize))
            ImGui.text("Workspaces: ${core.workspaces.size}   Open files: ${core.documents.openDocs.size}")
            ImGui.text("LSP servers: ${core.lsp.serversRunning().size} running")
        }
        ImGui.end()
    }

}