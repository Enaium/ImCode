package cn.enaium.imcode.ui

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiCond
import cn.enaium.imgui.ImGuiKey
import cn.enaium.imgui.ImGuiWindowFlags
import cn.enaium.imgui.ImVec2
import cn.enaium.imcode.app.AppCore
import cn.enaium.lsp.edit.DocPos

/**
 * Go to Line (Cmd/Ctrl+L), IntelliJ's small "line[:column]" prompt: type a
 * line, press Enter, the caret goes there. Accepts `12` or `12:5`, and both
 * are clamped to the document so a typo cannot scroll past the end.
 */
internal object GotoLineWindow {

    private var text = ""

    /** Called when the dialog opens, so it never reopens with the last line. */
    fun reset() {
        text = ""
    }

    fun draw(core: AppCore, ws: WorkspaceWindow?) {
        val openArr = BooleanArray(1) { core.showGotoLine }
        ImGui.setNextWindowSize(ImVec2(320f, 0f), ImGuiCond.FIRST_USE_EVER)
        val flags = ImGuiWindowFlags.ALWAYS_AUTO_RESIZE or ImGuiWindowFlags.NO_DOCKING
        if (!ImGui.begin("Go to Line", openArr, flags)) {
            ImGui.end()
            return
        }
        if (!openArr[0]) core.showGotoLine = false
        val doc = ws?.activeFile?.let { core.documents.get(it) }
        if (doc == null) {
            ImGui.textDisabled("No file open.")
            ImGui.end()
            return
        }

        ImGui.text("Line[:Column]")
        ImGui.setNextItemWidth(-1f)
        // The field owns the keyboard from the first frame, so the shortcut
        // that opened this does not also type into the editor.
        if (!ImGui.isAnyItemActive()) ImGui.setKeyboardFocusHere()
        text = ImGui.inputText("##goto-line", text) ?: text
        val submit = ImGui.isKeyPressed(ImGuiKey.ENTER, false)
        ImGui.textDisabled("1 - ${doc.editor.buffer.lineCount()}")
        if (submit) {
            jump(doc.editor, text)
            core.showGotoLine = false
        }
        ImGui.end()
    }

    /** Moves the caret to `line` (1-based) and optional `:column`. */
    internal fun jump(editor: cn.enaium.lsp.edit.Editor, text: String) {
        val target = target(text, editor.buffer.lineCount()) { editor.buffer.line(it).length } ?: return
        editor.setCursor(target)
    }

    /**
     * The caret position [text] asks for, or null when it is not a position.
     *
     * Both parts are 1-based and clamped: a typo must not scroll past the end
     * of the file, which is what a bare `toInt()` and `setCursor` would do.
     */
    internal fun target(text: String, lineCount: Int, lengthOf: (Int) -> Int): DocPos? {
        if (lineCount <= 0) return null
        val parts = text.trim().split(':')
        val line = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val column = parts.getOrNull(1)?.trim()?.toIntOrNull()
        val targetLine = (line - 1).coerceIn(0, lineCount - 1)
        val targetIndex = if (column == null) 0 else (column - 1).coerceIn(0, lengthOf(targetLine))
        return DocPos(targetLine, targetIndex)
    }
}
