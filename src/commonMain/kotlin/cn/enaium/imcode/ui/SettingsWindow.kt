package cn.enaium.imcode.ui

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiChildFlags
import cn.enaium.imgui.ImGuiCol
import cn.enaium.imgui.ImGuiTableColumnFlags
import cn.enaium.imgui.ImGuiWindowFlags
import cn.enaium.imgui.ImVec2
import cn.enaium.imgui.ImVec4
import cn.enaium.imcode.app.AppCore
import cn.enaium.imcode.config.KeyAction
import cn.enaium.imcode.config.Keymap
import cn.enaium.imcode.config.LspServer
import cn.enaium.imcode.util.ShellWords
import cn.enaium.lsp.edit.JetBrainsThemes

/**
 * Preferences window: fixed tab row on top + scrollable pane below, so the
 * tabs never leave the window (only the active pane scrolls).
 */
class SettingsWindow {
    private var selectedTab = 0
    private var capturingAction: String? = null

    // scratch state for the LSP server editor
    private var selectedServer = -1
    private var editName = "new-lsp"
    private var editPatterns = "*.kt, *.kts"
    private var editCommand = "kotlin-lsp"

    /** Combo label for lsp-edit's built-in Dark palette (empty theme name). */
    private val defaultThemeLabel = "Default (Dark)"

    /**
     * [ImGui.textDisabled] with wrapping — long hint lines must not widen
     * the window past the screen edge.
     */
    private fun textDisabledWrapped(text: String) {
        ImGui.pushStyleColor(ImGuiCol.TEXT, ImGui.getStyleColorVec4(ImGuiCol.TEXT_DISABLED))
        ImGui.textWrapped(text)
        ImGui.popStyleColor()
    }

    fun draw(core: AppCore) {
        if (!core.showSettings) return
        ImGui.setNextWindowSize(ImVec2(760f, 560f), cn.enaium.imgui.ImGuiCond.FIRST_USE_EVER)
        val openArr = BooleanArray(1) { core.showSettings }
        if (!ImGui.begin(
                "Settings",
                openArr,
                ImGuiWindowFlags.NO_SCROLLBAR or ImGuiWindowFlags.NO_SCROLL_WITH_MOUSE,
            )
        ) {
            ImGui.end()
            return
        }
        if (!openArr[0]) core.showSettings = false

        val avail = ImGui.getContentRegionAvail()
        val tabH = ImGui.getFrameHeight()
        val footerH = ImGui.getFrameHeight() + 6f
        val noScroll = ImGuiWindowFlags.NO_SCROLLBAR or ImGuiWindowFlags.NO_SCROLL_WITH_MOUSE

        // fixed tab row
        if (ImGui.beginChild("##st-tabs", ImVec2(avail.x, tabH), 0, noScroll)) {
            if (ImGui.beginTabBar("##settings-tabs")) {
                if (ImGui.beginTabItem("General")) { selectedTab = 0; ImGui.endTabItem() }
                if (ImGui.beginTabItem("Keymap")) { selectedTab = 1; ImGui.endTabItem() }
                if (ImGui.beginTabItem("LSP Servers")) { selectedTab = 2; ImGui.endTabItem() }
                ImGui.endTabBar()
            }
        }
        ImGui.endChild()

        // scrollable pane (buffered so borders never overflow the window)
        val paneH = (avail.y - tabH - footerH - 4f).coerceAtLeast(80f)
        if (ImGui.beginChild("##st-pane", ImVec2((avail.x - 4f).coerceAtLeast(200f), paneH), ImGuiChildFlags.BORDERS)) {
            when (selectedTab) {
                0 -> drawGeneral(core)
                1 -> drawKeymap(core)
                else -> drawLspServers(core)
            }
        }
        ImGui.endChild()

        // fixed footer
        if (ImGui.beginChild("##st-footer", ImVec2(avail.x, footerH), 0, noScroll)) {
            if (ImGui.button("Close")) core.showSettings = false
            ImGui.endChild()
        }
        ImGui.end()
    }

    private fun drawGeneral(core: AppCore) {
        var cfg = core.config
        val fontSize = FloatArray(1) { cfg.fontSize }
        if (ImGui.sliderFloat("Font size", fontSize, 10f, 24f, "%.1f")) {
            core.adjustFontTo(fontSize[0])
        }
        val rpc = BooleanArray(1) { cfg.rpcLogging }
        if (ImGui.checkbox("Log every LSP RPC message (Output > LSP tab)", rpc)) {
            cfg = cfg.copy(rpcLogging = rpc[0])
            core.updateConfig(cfg)

            core.saveState()
            }
        textDisabledWrapped("RPC logging is disabled by default; enable it while debugging language servers.")
        ImGui.separatorText("Startup")
        val restore = BooleanArray(1) { cfg.restoreWorkspacesOnStart }
        if (ImGui.checkbox("Reopen last session on startup", restore)) {
            cfg = cfg.copy(restoreWorkspacesOnStart = restore[0])
            core.updateConfig(cfg)
            core.saveState()
        }
        textDisabledWrapped("When enabled, ImCode reopens the projects from your previous session. When disabled, the project list page is shown at startup.")
        ImGui.separatorText("Editor")
        val ed = cfg.editor
        val tabSize = IntArray(1) { ed.tabSize }
        if (ImGui.sliderInt("Tab size", tabSize, 1, 8)) {
            cfg = cfg.copy(editor = ed.copy(tabSize = tabSize[0]))
            core.updateConfig(cfg)

            core.saveState()
            core.documents.applyEditorPrefs()
        }
        val insertSpaces = BooleanArray(1) { ed.insertSpaces }
        if (ImGui.checkbox("Insert spaces on tab", insertSpaces)) {
            cfg = cfg.copy(editor = ed.copy(insertSpaces = insertSpaces[0]))
            core.updateConfig(cfg)

            core.saveState()
            core.documents.applyEditorPrefs()
        }
        val wrap = BooleanArray(1) { ed.wordWrap }
        if (ImGui.checkbox("Word wrap", wrap)) {
            cfg = cfg.copy(editor = ed.copy(wordWrap = wrap[0]))
            core.updateConfig(cfg)

            core.saveState()
            core.documents.applyEditorPrefs()
        }
        val lineNumbers = BooleanArray(1) { ed.showLineNumbers }
        if (ImGui.checkbox("Show line numbers", lineNumbers)) {
            cfg = cfg.copy(editor = ed.copy(showLineNumbers = lineNumbers[0]))
            core.updateConfig(cfg)

            core.saveState()
            core.documents.applyEditorPrefs()
        }
        val minimap = BooleanArray(1) { ed.showMinimap }
        if (ImGui.checkbox("Show minimap", minimap)) {
            cfg = cfg.copy(editor = ed.copy(showMinimap = minimap[0]))
            core.updateConfig(cfg)

            core.saveState()
            core.documents.applyEditorPrefs()
        }
        val autoIndent = BooleanArray(1) { ed.autoIndent }
        if (ImGui.checkbox("Auto indent", autoIndent)) {
            cfg = cfg.copy(editor = ed.copy(autoIndent = autoIndent[0]))
            core.updateConfig(cfg)

            core.saveState()
            core.documents.applyEditorPrefs()
        }
        val lineFolding = BooleanArray(1) { ed.lineFolding }
        if (ImGui.checkbox("Line folding (gutter fold markers)", lineFolding)) {
            cfg = cfg.copy(editor = ed.copy(lineFolding = lineFolding[0]))
            core.updateConfig(cfg)

            core.saveState()
            core.documents.applyEditorPrefs()
        }
        ImGui.separatorText("Appearance")
        val themes = JetBrainsThemes.all
        val currentTheme = ed.theme
        val themeLabel = themes.firstOrNull { it.first == currentTheme }?.first ?: defaultThemeLabel
        if (ImGui.beginCombo("Theme##editor-theme", themeLabel)) {
            if (ImGui.selectable(defaultThemeLabel, currentTheme.isEmpty())) {
                cfg = cfg.copy(editor = ed.copy(theme = ""))
                core.updateConfig(cfg)

                core.saveState()
                core.documents.applyEditorPrefs()
            }
            for ((name, _) in themes) {
                if (ImGui.selectable(name, name == currentTheme)) {
                    cfg = cfg.copy(editor = ed.copy(theme = name))
                    core.updateConfig(cfg)

                    core.saveState()
                    core.documents.applyEditorPrefs()
                }
            }
            ImGui.endCombo()
        }
    }

    private fun drawKeymap(core: AppCore) {
        textDisabledWrapped("Shortcuts apply globally. 'Ctrl' is the primary modifier (Cmd on macOS).")
        ImGui.separator()
        if (ImGui.beginTable("##keymap", 3, 0, ImVec2(-1f, 0f))) {
            ImGui.tableSetupColumn("Action", ImGuiTableColumnFlags.WIDTH_FIXED, 220f)
            ImGui.tableSetupColumn("Shortcut", ImGuiTableColumnFlags.WIDTH_FIXED, 220f)
            ImGui.tableSetupColumn("", ImGuiTableColumnFlags.WIDTH_FIXED, 120f)
            ImGui.tableHeadersRow()
            for (action in KeyAction.ALL) {
                val display = Keymap.parse(core.config.effectiveShortcuts()[action] ?: "")?.display() ?: "unbound"
                ImGui.tableNextRow()
                ImGui.tableNextColumn()
                ImGui.text(KeyAction.label(action))
                ImGui.tableNextColumn()
                if (capturingAction == action) {
                    ImGui.textColored(ImVec4(1f, 0.85f, 0.4f, 1f), "Press a chord... (Esc cancels)")
                    handleCapture(core, action)
                } else {
                    ImGui.text(display)
                }
                ImGui.tableNextColumn()
                if (core.config.shortcuts.containsKey(action)) {
                    if (ImGui.smallButton("Reset##$action")) {
                        core.updateConfig(core.config.copy(shortcuts = core.config.shortcuts - action))

            core.saveState()
            }
                } else if (ImGui.smallButton("Edit##$action")) {
                    capturingAction = action
                }
            }
            ImGui.endTable()
        }
        ImGui.separator()
        if (ImGui.button("Reset all to IntelliJ defaults")) {
            core.updateConfig(core.config.copy(shortcuts = emptyMap()))

            core.saveState()
            }
    }

    private fun handleCapture(core: AppCore, action: String) {
        for ((name, code) in Keymap.allKeyNames()) {
            if (!ImGui.isKeyPressed(code, repeat = false)) continue
            if (name == "ESCAPE") {
                capturingAction = null
                return
            }
            val chord = Keymap.parse(name)
            if (chord != null) {
                core.updateConfig(core.config.copy(shortcuts = core.config.shortcuts + (action to chord.display())))

            core.saveState()
            capturingAction = null
                return
            }
        }
    }

    private fun drawLspServers(core: AppCore) {
        val servers = core.config.lspServers
        if (ImGui.beginTable("##servers", 4, 0, ImVec2(-1f, 180f))) {
            ImGui.tableSetupColumn("", ImGuiTableColumnFlags.WIDTH_FIXED, 24f)
            ImGui.tableSetupColumn("Name", ImGuiTableColumnFlags.WIDTH_FIXED, 140f)
            ImGui.tableSetupColumn("Patterns", ImGuiTableColumnFlags.WIDTH_STRETCH, 0f)
            ImGui.tableSetupColumn("Command", ImGuiTableColumnFlags.WIDTH_STRETCH, 0f)
            ImGui.tableHeadersRow()
            servers.forEachIndexed { index, server ->
                ImGui.tableNextRow()
                ImGui.tableNextColumn()
                if (ImGui.radioButton("##sel-$index", selectedServer == index)) {
                    selectedServer = index
                    // Sync the scratch buffers with the selected server so
                    // the editor below shows ITS values, never leftovers
                    // from a previously edited/added server.
                    editName = server.name
                    editPatterns = server.patterns.joinToString(", ")
                    editCommand = server.commandString ?: ShellWords.join(server.command)
                }
                ImGui.tableNextColumn()
                ImGui.text(server.name)
                ImGui.tableNextColumn()
                ImGui.text(server.patterns.joinToString(", "))
                ImGui.tableNextColumn()
                ImGui.text(server.commandString ?: ShellWords.join(server.command))
            }
            ImGui.endTable()
        }
        // Own row: sameLine() right after endTable() placed these past the
        // full-width table's right edge, off screen.
        if (ImGui.button("Add")) {
            selectedServer = servers.size
            editName = "new-lsp"
            editPatterns = "*.txt"
            editCommand = "example-lsp"
        }
        ImGui.sameLine()
        if (ImGui.button("Remove") && selectedServer in servers.indices) {
            core.updateConfig(core.config.copy(lspServers = servers.filterIndexed { i, _ -> i != selectedServer }))

            core.saveState()
            selectedServer = -1
            core.reloadLspServers()
        }

        ImGui.separatorText("Selected server")
        if (selectedServer >= 0 && selectedServer < servers.size) {
            val server = servers[selectedServer]
            val name = (editName.ifBlank { server.name })
            val patterns = (editPatterns.ifBlank { server.patterns.joinToString(", ") })
            val command = (editCommand.ifBlank { server.commandString ?: ShellWords.join(server.command) })

            ImGui.text("Name")
            ImGui.sameLine()
            editName = ImGui.inputText("##name-$selectedServer", name) ?: name
            ImGui.text("Patterns (comma separated, e.g. *.kt, *.kts)")
            editPatterns = ImGui.inputText("##patterns-$selectedServer", patterns) ?: patterns
            ImGui.text("Command line (quoted args allowed)")
            editCommand = ImGui.inputText("##cmd-$selectedServer", command) ?: command

            // Compare in the same space the save path uses: tokens. This
            // keeps quote style / extra spaces from counting as a change
            // (which would rewrite the config and restart the server every
            // frame while the settings window is open).
            val patternsChanged = editPatterns.split(',').map { it.trim() }.filter { it.isNotBlank() } != server.patterns
            // Compare the raw strings: the edit box holds the verbatim text
            // (quotes preserved). A quote-only edit (same tokens, different
            // quoting) must still count as a change so commandString is kept.
            val commandChanged = editCommand != (server.commandString ?: ShellWords.join(server.command))
            val nameChanged = editName != server.name
            if (nameChanged || patternsChanged || commandChanged) {
                val newPatterns = editPatterns.split(',').map { it.trim() }.filter { it.isNotBlank() }
                val newCommand = ShellWords.split(editCommand).ifEmpty { server.command }
                val updated = server.copy(
                    name = editName,
                    patterns = newPatterns,
                    command = newCommand,
                    commandString = editCommand,
                )
                core.updateConfig(core.config.copy(lspServers = servers.toMutableList().also { it[selectedServer] = updated }))

            core.saveState()
            if (patternsChanged || commandChanged) core.reloadLspServers()
            }
        } else if (selectedServer == servers.size) {
            ImGui.text("New server")
            editName = ImGui.inputText("##new-name", editName) ?: editName
            editPatterns = ImGui.inputText("##new-patterns", editPatterns) ?: editPatterns
            editCommand = ImGui.inputText("##new-cmd", editCommand) ?: editCommand
            if (ImGui.button("Create server")) {
                val patterns = editPatterns.split(',').map { it.trim() }.filter { it.isNotBlank() }
                val command = ShellWords.split(editCommand).ifEmpty { listOf("example-lsp") }
                val server = LspServer(
                    name = editName.ifBlank { "server" },
                    patterns = patterns.ifEmpty { listOf("*.txt") },
                    command = command,
                    commandString = editCommand,
                )
                core.updateConfig(core.config.copy(lspServers = servers + server))

            core.saveState()
            selectedServer = servers.size
                core.reloadLspServers()
            }
        }

        ImGui.separator()
        if (ImGui.button("Restart all LSP servers")) core.reloadLspServers()
    }
}