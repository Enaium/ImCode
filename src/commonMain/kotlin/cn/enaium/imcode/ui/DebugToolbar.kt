package cn.enaium.imcode.ui

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiCond
import cn.enaium.imgui.ImGuiWindowFlags
import cn.enaium.imgui.ImVec2
import cn.enaium.imcode.app.AppCore
import cn.enaium.xicons.imgui.Icon
import cn.enaium.xicons.imgui.icons.intellij.ExpuiRunPauseDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiRunRestartDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiRunResumeDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiRunStepIntoDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiRunStepOutDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiRunStepOverDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiRunStopDark

/**
 * The debug toolbar: the six actions a debug session needs, VS Code style —
 * continue/pause, step over, step into, step out, restart, stop — plus the
 * adapter's name and where the program stopped.
 *
 * A plain ImGui window: its title bar drags it anywhere, and it floats over
 * the editor instead of pushing it down like a strip would.
 *
 * Shown only while a session is active; starting one is a menu action / key
 * (F5), the way an IDE separates "run" from "what to do once stopped".
 */
internal object DebugToolbar {

    /**
     * True when [ws] is the workspace the toolbar belongs to: a session's
     * workspace while one runs, otherwise the one whose file can be debugged.
     *
     * Debug state is process-wide, so asking [AppCore.activeDoc] here showed
     * the toolbar in every window — a Kotlin project with no debug support
     * displayed a Python project's session.
     */
    private fun visible(core: AppCore, ws: WorkspaceWindow): Boolean {
        val debugged = core.debug.debuggedPath()
        if (core.debug.session != null && debugged != null) return debugged.startsWith(ws.dir)
        val doc = ws.activeFile?.let { core.documents.get(it) }
        return core.debug.canDebug(doc)
    }

    fun draw(core: AppCore, ws: WorkspaceWindow) {
        if (!visible(core, ws)) return
        // First appearance near the top of the editor; after that ImGui owns
        // the position (dragging the title bar).
        val display = ImGui.getIO().displaySize
        ImGui.setNextWindowPos(ImVec2(display.x / 2f, 12f), ImGuiCond.FIRST_USE_EVER)
        // Auto-sized to the row of buttons, and not dockable: this is a
        // floating toolbar, not a pane.
        val flags = ImGuiWindowFlags.ALWAYS_AUTO_RESIZE or ImGuiWindowFlags.NO_DOCKING
        if (!ImGui.begin("Debug", null, flags)) {
            // Begin and End pair up even when Begin reports the window is
            // collapsed or clipped; skipping this End leaves the next window's
            // End unbalanced ("Calling End() too many times!").
            ImGui.end()
            return
        }
        val debug = core.debug
        val running = debug.running

        // Continue / pause: one button that flips with the session state.
        if (iconButton("resume", if (running) ExpuiRunPauseDark else ExpuiRunResumeDark)) {
            debug.continueOrPause()
        }
        tooltip(if (running) "Pause (F6)" else "Continue (F5)")

        if (iconButton("step-over", ExpuiRunStepOverDark, enabled = !running)) debug.stepOver()
        tooltip("Step Over (F10)")

        if (iconButton("step-into", ExpuiRunStepIntoDark, enabled = !running)) debug.stepInto()
        tooltip("Step Into (F11)")

        if (iconButton("step-out", ExpuiRunStepOutDark, enabled = !running)) debug.stepOut()
        tooltip("Step Out (Shift+F11)")

        if (iconButton("restart", ExpuiRunRestartDark, enabled = debug.active)) debug.restart()
        tooltip("Restart (Ctrl+Shift+F5)")

        if (iconButton("stop", ExpuiRunStopDark, enabled = debug.active)) debug.stop()
        tooltip("Stop (Shift+F5)")

        // Where we are: adapter, stop reason and position. Also the toolbar's
        // drag handle — text is not an item the mouse is captured by, so
        // dragging it moves the window.
        val session = debug.session
        val status = debug.status.ifBlank {
            when {
                session == null -> {
                    val adapter = core.activeDoc()?.let { debug.adapterFor(it) }
                    if (adapter != null) "${adapter.name} ready — F5 to debug" else ""
                }
                running -> "${debug.adapterName}: running"
                else -> {
                    val line = session.stackFrames.firstOrNull()?.line
                    val where = if (line != null) "line $line" else "paused"
                    "${debug.adapterName}: ${session.stopReason.ifBlank { "stopped" }} at $where"
                }
            }
        }
        if (status.isNotEmpty()) {
            ImGui.sameLine()
            ImGui.textDisabled(status)
        }

        ImGui.end()
    }

    /** A square icon button of the debug row, kept on the same line. */
    private fun iconButton(id: String, icon: Icon, enabled: Boolean = true): Boolean {
        val clicked = IconLayout.iconButton("debug-$id", icon, enabled = enabled)
        ImGui.sameLine(0f, 2f)
        return clicked
    }

    private fun tooltip(text: String) {
        if (ImGui.isItemHovered()) ImGui.setTooltip(text)
    }
}
