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
        drawStatusBar()
    }

    /** Workspace mode: only the floating aux windows (settings/search/about/...). */
    fun drawAux() {
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
        drawConfirmModal()
        core.fileDialogs.render()
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

    private fun drawStatusBar() {
        val screenW = ImGui.getIO().displaySize.x
        val screenH = ImGui.getIO().displaySize.y
        val barH = 26f
        ImGui.setCursorPos(ImVec2(0f, screenH - barH))
        ImGui.beginChild(
                "##statusbar",
                ImVec2(screenW, barH),
                ImGuiChildFlags.BORDERS,
                ImGuiWindowFlags.NO_TITLE_BAR or ImGuiWindowFlags.NO_MOVE or ImGuiWindowFlags.NO_RESIZE or
                    ImGuiWindowFlags.NO_SCROLLBAR or ImGuiWindowFlags.NO_SAVED_SETTINGS,
            )
        val doc = core.activeDoc()
        if (doc != null) {
            val cursor = doc.editor.cursor
            ImGui.text("Ln ${cursor.line + 1}, Col ${cursor.index + 1}")
            ImGui.sameLine()
            ImGui.text(if (doc.dirty) "modified" else "saved")
            ImGui.sameLine()
            ImGui.text("${doc.errorCount}E ${doc.warningCount}W")
            ImGui.sameLine()
            ImGui.text(doc.languageId)
        } else {
            ImGui.text("Ready")
        }
        // Work-done progress (e.g. kotlin-lsp indexing): a bar with the
        // server-provided title/message/percentage while work is active.
        core.lsp.activeProgress().firstOrNull()?.let { p ->
            ImGui.sameLine()
            val overlay = buildString {
                append(p.title)
                p.message?.let { if (isNotEmpty()) append(" — "); append(it) }
                p.percentage?.let { append(" "); append(it); append("%") }
            }
            ImGui.progressBar((p.percentage ?: 0) / 100f, ImVec2(200f, 0f), overlay)
        }
        ImGui.sameLine(screenW - 460f)
        ImGui.textDisabled(core.activeServerStatus())
        ImGui.sameLine(screenW - 120f)
        ImGui.text("font %.1f".format(core.config.fontSize))
        ImGui.endChild()
    }
}