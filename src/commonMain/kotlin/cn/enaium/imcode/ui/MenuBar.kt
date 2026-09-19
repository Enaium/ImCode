package cn.enaium.imcode.ui

import cn.enaium.imgui.ImGui
import cn.enaium.imcode.app.AppCore
import cn.enaium.imcode.config.KeyAction
import cn.enaium.imcode.editor.Document

/**
 * The application menu bar. Drawn by the hub window (ws = null) and by every
 * workspace window (ws = that workspace); actions target the local workspace.
 */
fun drawMenuBar(core: AppCore, ws: WorkspaceWindow?) {
    if (!ImGui.beginMainMenuBar()) return
    val doc: Document? = ws?.activeFile?.let { core.documents.get(it) }

    if (ImGui.beginMenu("File")) {
        if (ImGui.menuItem("New File...", shortcutFor(core, KeyAction.NEW_FILE), false, ws != null)) {
            ws?.promptNewFile()
        }
        if (ImGui.menuItem("Open File...", shortcutFor(core, KeyAction.OPEN_FILE))) core.requestOpenFile(ws)
        if (ImGui.menuItem("Open Folder...", shortcutFor(core, KeyAction.OPEN_FOLDER))) core.requestOpenFolder()
        ImGui.separator()
        if (ImGui.menuItem("Save", shortcutFor(core, KeyAction.SAVE), false, doc != null)) doc?.let { core.documents.save(it) }
        if (ImGui.menuItem("Save All", "", false, core.documents.openDocs.any { it.dirty })) core.documents.saveAll()
        ImGui.separator()
        if (ImGui.menuItem("Close Project", "", false, ws != null)) {
            // close the current workspace; when it was the last one the app
            // falls back to the project list page automatically
            ws?.let { core.requestWorkspaceClose(it) }
        }
        ImGui.separator()
        if (ImGui.menuItem("Exit")) core.requestExit()
        ImGui.endMenu()
    }
    if (ImGui.beginMenu("Edit")) {
        val enabled = doc != null
        if (ImGui.menuItem("Undo", "Ctrl+Z", false, enabled)) doc?.let { it.editor.undo() }
        if (ImGui.menuItem("Redo", "Ctrl+Shift+Z", false, enabled)) doc?.let { it.editor.redo() }
        ImGui.separator()
        if (ImGui.menuItem("Cut", "Ctrl+X", false, enabled)) doc?.let { it.editor.cut() }
        if (ImGui.menuItem("Copy", "Ctrl+C", false, enabled)) doc?.let { it.editor.copy() }
        if (ImGui.menuItem("Paste", "Ctrl+V", false, enabled)) doc?.let { it.editor.paste() }
        if (ImGui.menuItem("Select All", "Ctrl+A", false, enabled)) doc?.let { it.editor.selectAll() }
        ImGui.separator()
        if (ImGui.menuItem("Invoke Completion", "Ctrl+Space", false, enabled)) doc?.let { core.documents.invokeCompletion(it) }
        ImGui.separator()
        if (ImGui.menuItem("Rename Symbol", "F2", false, enabled)) ws?.openRename(core)
        if (ImGui.menuItem("Find References", "Shift+F12", false, enabled)) ws?.openReferences(core)
        ImGui.endMenu()
    }
    if (ImGui.beginMenu("Search")) {
        if (ImGui.menuItem("Find in Files...", shortcutFor(core, KeyAction.FIND_FILES))) {
            core.showSearch = true
        }
        if (ImGui.menuItem("Find Symbol...")) {
            core.showSymbolSearch = true
        }
        ImGui.endMenu()
    }
    if (ImGui.beginMenu("View")) {
        if (ImGui.menuItem("Settings", shortcutFor(core, KeyAction.SETTINGS))) core.showSettings = true
        ImGui.separator()
        if (ImGui.menuItem("Increase Font Size", shortcutFor(core, KeyAction.FONT_UP))) core.adjustFont(+0.5f)
        if (ImGui.menuItem("Decrease Font Size", shortcutFor(core, KeyAction.FONT_DOWN))) core.adjustFont(-0.5f)
        if (ImGui.menuItem("Reset Font Size", shortcutFor(core, KeyAction.FONT_RESET))) core.adjustFontTo(13f)
        ImGui.separator()
        if (ImGui.menuItem("Welcome Screen", shortcutFor(core, KeyAction.WELCOME))) core.showWelcome = true
        ImGui.endMenu()
    }
    if (ImGui.beginMenu("Workspace")) {
        if (ImGui.menuItem("New Workspace Window...", shortcutFor(core, KeyAction.NEW_WORKSPACE))) core.requestOpenFolder()
        if (core.workspaces.isNotEmpty()) {
            ImGui.separator()
            for (w in core.workspaces.toList()) {
                if (ImGui.menuItem(w.title(), "", core.focusedWorkspace == w)) core.focusWorkspace(w)
            }
        }
        if (ws != null) {
            ImGui.separator()
            if (ImGui.menuItem("Close Current Workspace")) core.requestWorkspaceClose(ws)
        }
        ImGui.endMenu()
    }
    if (ImGui.beginMenu("Help")) {
        if (ImGui.menuItem("About ImCode")) core.showAbout = true
        ImGui.endMenu()
    }
    ImGui.endMainMenuBar()
}

private fun shortcutFor(core: AppCore, action: String): String =
    cn.enaium.imcode.config.Keymap.parse(core.config.effectiveShortcuts()[action] ?: "")?.display() ?: ""

/**
 * Processes global-ish chords for one window. Chords target [ws] (null for
 * the hub window -> welcome/global actions only).
 */
fun processWindowShortcuts(core: AppCore, ws: WorkspaceWindow?) {
    val shortcuts = core.config.effectiveShortcuts()
    val doc = ws?.activeFile?.let { core.documents.get(it) }

    fun fire(action: String) {
        val chord = shortcuts[action]?.let { cn.enaium.imcode.config.Keymap.parse(it) } ?: return
        if (cn.enaium.imcode.config.Keymap.pressed(chord)) runAction(core, action, ws, doc)
    }
    for (action in KeyAction.ALL) fire(action)

    // Ctrl/Cmd+Space completion (the editor owns its popup; we feed suggestions)
    if (doc != null && primaryModDown() && ImGui.isKeyPressed(cn.enaium.imgui.ImGuiKey.SPACE, false)) {
        core.documents.invokeCompletion(doc)
    }
    // F2: rename symbol under the caret
    if (doc != null && ws != null && ImGui.isKeyPressed(cn.enaium.imgui.ImGuiKey.F2, false)) {
        ws.openRename(core)
    }
    // Shift+F12: find references
    if (doc != null && ws != null && ImGui.isKeyPressed(cn.enaium.imgui.ImGuiKey.F12, false) &&
        (ImGui.isKeyDown(cn.enaium.imgui.ImGuiKey.LEFT_SHIFT) || ImGui.isKeyDown(cn.enaium.imgui.ImGuiKey.RIGHT_SHIFT))
    ) {
        ws.openReferences(core)
    }
}

private fun primaryModDown(): Boolean {
    val superDown = ImGui.isKeyDown(cn.enaium.imgui.ImGuiKey.LEFT_SUPER) || ImGui.isKeyDown(cn.enaium.imgui.ImGuiKey.RIGHT_SUPER)
    val ctrlDown = ImGui.isKeyDown(cn.enaium.imgui.ImGuiKey.LEFT_CTRL) || ImGui.isKeyDown(cn.enaium.imgui.ImGuiKey.RIGHT_CTRL)
    return if (cn.enaium.imcode.platform.Platform.isMac) superDown else ctrlDown
}

internal fun runAction(core: AppCore, action: String, ws: WorkspaceWindow?, doc: Document?) {
    when (action) {
        KeyAction.NEW_FILE -> ws?.promptNewFile()
        KeyAction.OPEN_FILE -> core.requestOpenFile(ws)
        KeyAction.OPEN_FOLDER -> core.requestOpenFolder()
        KeyAction.SAVE -> doc?.let { core.documents.save(it) }
        KeyAction.SAVE_ALL -> core.documents.saveAll()
        KeyAction.CLOSE_TAB -> ws?.activeFile?.let { path -> if (core.documents.isOpen(path)) ws.requestClose(path) }
        KeyAction.FIND_FILES -> core.showSearch = true
        KeyAction.RECENT_FILES -> core.showWelcome = true
        KeyAction.SETTINGS -> core.showSettings = true
        KeyAction.FONT_UP -> core.adjustFont(+0.5f)
        KeyAction.FONT_DOWN -> core.adjustFont(-0.5f)
        KeyAction.FONT_RESET -> core.adjustFontTo(13f)
        KeyAction.NEW_WORKSPACE -> core.requestOpenFolder()
        KeyAction.WELCOME -> core.showWelcome = true
        KeyAction.QUICK_FIX -> if (doc != null) ws?.openCodeActions(core)
    }
}
