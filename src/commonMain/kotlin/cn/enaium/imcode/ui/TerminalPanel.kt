package cn.enaium.imcode.ui

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiTabItemFlags
import cn.enaium.imcode.app.OutputLog
import cn.enaium.terminal.core.Terminal
import cn.enaium.terminal.imgui.ImGuiTerminal
import cn.enaium.terminal.imgui.TerminalFonts
import cn.enaium.terminal.pty.PtyConfig
import cn.enaium.terminal.session.TerminalSession

/**
 * Bottom console pane: shells behind pseudo-terminals, one per tab, rendered
 * by terminal-emulator-kmp's ImGui widget.
 *
 * A tab's session spawns when the tab opens (working directory = the workspace
 * root) and is pumped once per frame on the UI thread — the only thread that
 * touches the terminal state. The tab bar carries the "+" that opens another
 * shell, and a tab disappears when its × is clicked or its shell exits. The
 * widget consumes the keyboard while the pane has focus; text and wheel events
 * arrive from the SDL loop through [textInput] and [wheel].
 */
class TerminalPanel(private val workingDirectory: String) {

    /** One shell and the widget that draws it. */
    private class Tab(val id: Int, val session: TerminalSession) {
        /** ImGui's close state; cleared when the tab's × is clicked. */
        val open = BooleanArray(1) { true }
        var widget: ImGuiTerminal? = null
    }

    /**
     * The hosting ImGui context's terminal faces. A rebuilt atlas hands over a
     * new instance, which drops the widgets so they are rebuilt against it.
     */
    var fonts: TerminalFonts? = null
        set(value) {
            if (field !== value) {
                field = value
                tabs.forEach { it.widget = null }
            }
        }

    private val tabs = mutableListOf<Tab>()
    private var nextId = 1

    /** The tab whose body was drawn last; input goes to it. */
    private var active: Tab? = null

    /** Id of the tab to select on the next frame (the one the "+" opened). */
    private var selectNext = 0

    /** True until the pane has opened its first shell. */
    private var started = false

    /** True while the pointer is over the pane; only then does the wheel scroll it. */
    private var hovered = false

    /** True while the active terminal owns the keyboard: the host starts SDL text input for it. */
    val wantsTextInput: Boolean get() = active?.widget?.wantsTextInput == true

    /** True while the active tab's shell is alive. */
    val isRunning: Boolean get() = active?.session?.isRunning == true

    /** Number of open terminals. */
    val tabCount: Int get() = tabs.size

    /** The active tab's terminal state (grid, cursor, modes); exposed for tests. */
    internal val terminalState: Terminal? get() = active?.session?.terminal

    /**
     * Opens another shell in its own tab. [command] defaults to the platform
     * shell; passing one runs that command instead (e.g. a task in a tab).
     */
    fun newTab(command: List<String> = emptyList()) {
        val session = TerminalSession.spawn(PtyConfig(command = command, workingDirectory = workingDirectory))
        OutputLog.info(
            "Terminal",
            (if (command.isEmpty()) "shell" else command.joinToString(" ")) + " in $workingDirectory",
        )
        val tab = Tab(nextId++, session)
        tabs += tab
        active = tab
        selectNext = tab.id
    }

    /** Kills every shell (the workspace is closing). */
    fun close() {
        tabs.forEach { it.session.close() }
        tabs.clear()
        active = null
    }

    fun draw() {
        val fonts = fonts
        if (fonts == null) {
            ImGui.textDisabled("  Terminal fonts are not ready.")
            return
        }
        // A fresh pane opens one shell; once every tab is closed it stays
        // empty until the "+" opens another one.
        if (!started) {
            started = true
            newTab()
        }
        drawTabBar()
        dropClosedTabs()
        val tab = active ?: tabs.firstOrNull()?.also { active = it } ?: return
        // The pty reader only queues bytes; applying them is UI-thread work.
        tab.session.pump()
        val terminal = tab.widget ?: ImGuiTerminal(tab.session, fonts).also { tab.widget = it }
        // Drawn directly in the pane window, not in a child: ImGui's focus
        // model never focuses a child window, so a child would leave the
        // widget's isFocused false and swallow every keystroke.
        terminal.drawContent(
            ImGui.getWindowDrawList(),
            ImGui.getCursorScreenPos(),
            ImGui.getContentRegionAvail(),
        )
        hovered = ImGui.isWindowHovered()
    }

    /** Forwards typed text; the widget ignores it unless it has the focus. */
    fun textInput(text: String) {
        active?.widget?.textInput(text)
    }

    /** Forwards a wheel event; the pane only scrolls while the pointer is over it. */
    fun wheel(delta: Float) {
        if (hovered) active?.widget?.handleWheel(delta)
    }

    private fun drawTabBar() {
        if (!ImGui.beginTabBar("##imcode-terminals", 0)) return
        for (tab in tabs) {
            val flags = if (selectNext == tab.id) ImGuiTabItemFlags.SET_SELECTED else 0
            // OSC 2 renames the tab; ### keeps its ImGui id stable across renames.
            val title = tab.session.terminal.title?.takeIf { it.isNotBlank() } ?: "Terminal ${tab.id}"
            if (ImGui.beginTabItem("$title###imcode-terminal-${tab.id}", tab.open, flags)) {
                active = tab
                ImGui.endTabItem()
            }
        }
        selectNext = 0
        if (ImGui.tabItemButton("+", ImGuiTabItemFlags.TRAILING)) newTab()
        ImGui.endTabBar()
    }

    /** A tab goes away when its × was clicked or its shell exited. */
    private fun dropClosedTabs() {
        val closed = tabs.filter { !it.open[0] || !it.session.isRunning }
        if (closed.isEmpty()) return
        for (tab in closed) {
            tab.session.close()
            tabs.remove(tab)
            if (active === tab) active = null
        }
        if (active == null) selectNext = tabs.firstOrNull()?.id ?: 0
    }
}
