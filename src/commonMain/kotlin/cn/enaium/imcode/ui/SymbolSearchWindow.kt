package cn.enaium.imcode.ui

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiTableColumnFlags
import cn.enaium.imgui.ImVec2
import cn.enaium.imcode.app.AppCore
import cn.enaium.lsp.model.SymbolLocation
import cn.enaium.lsp.model.WorkspaceSymbol

/**
 * Workspace symbol search window (`workspace/symbol`): fuzzy search across
 * every running language server, showing the symbol kind + container, with
 * click-to-navigate.
 */
class SymbolSearchWindow {
    private var query = ""
    private var symbols: List<WorkspaceSymbol> = emptyList()
    private var searching = false
    private var searchRequested = false

    fun draw(core: AppCore) {
        ImGui.setNextWindowSize(ImVec2(520f, 400f), cn.enaium.imgui.ImGuiCond.ONCE)
        // A non-null p_open is what makes ImGui draw the title-bar close
        // button; passing null disables it entirely.
        val openArr = BooleanArray(1) { core.showSymbolSearch }
        if (!ImGui.begin("Symbol Search", openArr, 0)) {
            ImGui.end()
            return
        }
        if (!openArr[0]) {
            core.showSymbolSearch = false
        }
        ImGui.text("Search symbols across the workspace:")
        ImGui.setNextItemWidth(-1f)
        val prev = query
        query = ImGui.inputText("##symbol-query", query) ?: query
        if (query != prev && query.isNotBlank()) {
            searchRequested = true
        }
        if (searchRequested && !searching) {
            searching = true
            searchRequested = false
            val q = query.trim()
            core.lsp.requestWorkspaceSymbols(q) { result ->
                symbols = result
                searching = false
            }
        }
        if (searching) {
            ImGui.textDisabled("Searching...")
        } else if (query.isBlank()) {
            ImGui.textDisabled("Type at least one character.")
        } else if (symbols.isEmpty()) {
            ImGui.textDisabled("No symbols found.")
        } else {
            if (ImGui.beginTable("##symbol-table", 3, cn.enaium.imgui.ImGuiTableFlags.ROW_BG)) {
                ImGui.tableSetupColumn("Symbol", ImGuiTableColumnFlags.WIDTH_STRETCH, 180f)
                ImGui.tableSetupColumn("Kind", ImGuiTableColumnFlags.WIDTH_FIXED, 90f)
                ImGui.tableSetupColumn("Container", ImGuiTableColumnFlags.WIDTH_STRETCH, 0f)
                ImGui.tableHeadersRow()
                for ((i, sym) in symbols.take(200).withIndex()) {
                    ImGui.tableNextRow()
                    ImGui.tableNextColumn()
                    val label = sym.name
                    // The row index keeps IDs unique: overloads share the
                    // same name AND kind, so name+kind collided (ImGui ID
                    // conflict) whenever a result list contained them.
                    if (ImGui.selectable(label + "##sym-" + i, false, 0, ImVec2(0f, 0f))) {
                        navigate(core, sym)
                    }
                    ImGui.tableNextColumn()
                    ImGui.textDisabled(symbolKindName(sym.kind))
                    ImGui.tableNextColumn()
                    val container = sym.containerName
                    if (container != null) ImGui.textDisabled(container)
                }
                ImGui.endTable()
            }
        }
        ImGui.end()
    }

    private fun navigate(core: AppCore, sym: WorkspaceSymbol) {
        val loc = sym.location ?: return
        when (loc) {
            is SymbolLocation.LocationValue -> {
                val l = loc.value
                core.openLocation(
                    cn.enaium.imcode.util.Uri.uriToPath(l.uri) ?: l.uri,
                    l.range.start.line,
                    l.range.start.character,
                )
            }
            is SymbolLocation.Workspace -> {
                // No range: open the file at the top.
                core.openLocation(
                    cn.enaium.imcode.util.Uri.uriToPath(loc.value.uri) ?: loc.value.uri,
                    0,
                    0,
                )
            }
        }
    }

    private fun symbolKindName(kind: Int): String = when (kind) {
        1 -> "File"
        2 -> "Module"
        3 -> "Namespace"
        4 -> "Package"
        5 -> "Class"
        6 -> "Method"
        7 -> "Property"
        8 -> "Field"
        9 -> "Constructor"
        10 -> "Enum"
        11 -> "Interface"
        12 -> "Function"
        13 -> "Variable"
        14 -> "Constant"
        15 -> "String"
        16 -> "Number"
        17 -> "Boolean"
        18 -> "Array"
        19 -> "Object"
        20 -> "Key"
        21 -> "Null"
        22 -> "EnumMember"
        23 -> "Struct"
        24 -> "Event"
        25 -> "Operator"
        26 -> "TypeParameter"
        else -> "?"
    }
}
