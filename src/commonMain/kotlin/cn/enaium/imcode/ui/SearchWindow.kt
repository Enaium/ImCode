package cn.enaium.imcode.ui

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiTableColumnFlags
import cn.enaium.imgui.ImVec2
import cn.enaium.imgui.ImVec4
import cn.enaium.imcode.app.AppCore
import cn.enaium.imcode.app.Mailbox
import cn.enaium.imcode.search.SearchHit
import cn.enaium.imcode.platform.ioFile
import cn.enaium.imcode.search.SearchService

/** Global search window: file-name search and content search. */
class SearchWindow(
    private val service: SearchService,
    private val mailbox: Mailbox,
) {
    private var modeIndex = 0
    private var query = ""
    private var hits: List<SearchHit> = emptyList()
    private var searching = false
    private var searchRoot = ""
    private var modeChanged = false

    init {
        service.onResults = { _, result ->
            mailbox.post {
                // SearchService cancels the previous job before starting a new
                // one, so only the latest search reports completion. Accept the
                // result unconditionally: the service-side search id and this
                // window's request counter were never guaranteed to stay in
                // sync (a rebuilt window restarts its counter while the service
                // id keeps increasing), which silently dropped every result.
                hits = result
                searching = false
                cn.enaium.imcode.app.OutputLog.append(
                    "Search", cn.enaium.imcode.app.LogLevel.INFO,
                    "search finished: ${result.size} hits",
                )
            }
        }
    }

    fun draw(core: AppCore) {
        if (!core.showSearch) return
        ImGui.setNextWindowSize(ImVec2(720f, 480f), cn.enaium.imgui.ImGuiCond.FIRST_USE_EVER)
        val openArr = BooleanArray(1) { core.showSearch }
        if (!ImGui.begin("Find in Files", openArr)) {
            ImGui.end()
            return
        }
        if (!openArr[0]) core.showSearch = false

        val root = scopeDir ?: core.activeWorkspace()?.dir ?: ""

        // ---- input row: input + mode combo + Cancel, nothing else — the
        // combo keeps a FIXED narrow width and the input field takes whatever
        // remains, so the row can never overflow regardless of window width.
        val comboW = 110f
        val cancelW = 60f
        val spacing = 16f // two sameLine gaps at the default item spacing
        val inputW = (ImGui.getContentRegionAvail().x - comboW - cancelW - spacing).coerceAtLeast(120f)
        ImGui.setNextItemWidth(inputW)
        val typed = ImGui.inputTextWithHint("##query", "Search files or content...", query)
        val queryChanged = typed != null && typed != query
        if (typed != null) query = typed
        val enterPressed = ImGui.isItemActive() && ImGui.isKeyPressed(cn.enaium.imgui.ImGuiKey.ENTER, false)
        ImGui.sameLine()
        val modeLabels = arrayOf("File names", "Content")
        val modeArr = IntArray(1) { modeIndex }
        ImGui.setNextItemWidth(comboW)
        if (ImGui.combo("##mode", modeArr, modeLabels)) {
            if (modeArr[0] != modeIndex) modeChanged = true
            modeIndex = modeArr[0]
        }
        ImGui.sameLine()
        if (ImGui.button("Cancel", ImVec2(cancelW, 0f))) service.cancel()

        // auto-search on every edit (with an implicit debounce by not racing:
        // a stale result is still rendered until the new one lands) and when
        // the mode switches — no explicit "Search" button needed
        if (queryChanged || modeChanged || enterPressed) {
            if (query.isNotBlank() && root.isNotBlank()) runSearch(root)
        }

        ImGui.separator()

        // ---- status lines: search scope on its own clipped line, then the
        // honest result state (never a misleading "No results") ----
        if (root.isBlank()) {
            ImGui.textColored(ImVec4(1f, 0.6f, 0.4f, 1f), "No workspace folder to search - open a workspace first")
        } else {
            val inLabel = "in: " + root
            ImGui.textDisabled(clipToWidth(inLabel, ImGui.getContentRegionAvail().x))
            if (ImGui.isItemHovered()) ImGui.setTooltip(inLabel)
        }
        if (query.isBlank()) {
            ImGui.textDisabled("Type above to search")
        } else if (searching) {
            ImGui.textColored(ImVec4(0.9f, 0.8f, 0.4f, 1f), "Searching...")
        } else if (hits.isEmpty()) {
            ImGui.textColored(ImVec4(1f, 0.7f, 0.4f, 1f), "No matching results")
        } else {
            ImGui.textDisabled("${hits.size} result${if (hits.size == 1) "" else "s"} in ${searchRoot.takeLast(40)}")
        }

        if (ImGui.beginTable("##search-results", 3, 0, ImVec2(-1f, -1f))) {
            ImGui.tableSetupColumn("File", ImGuiTableColumnFlags.WIDTH_STRETCH, 0f)
            ImGui.tableSetupColumn("Line", ImGuiTableColumnFlags.WIDTH_FIXED, 60f)
            ImGui.tableSetupColumn("Match", ImGuiTableColumnFlags.WIDTH_STRETCH, 0f)
            ImGui.tableHeadersRow()
            for (hit in hits) {
                ImGui.tableNextRow()
                ImGui.tableNextColumn()
                val rel = if (searchRoot.isNotEmpty()) {
                    runCatching { ioFile(searchRoot).relativize(ioFile(hit.path)) }.getOrNull() ?: hit.path
                } else hit.path
                // Line disambiguates content-search hits: several rows can
                // share one file path (same file, different matches).
                val selected = ImGui.selectable(rel + "##" + hit.path + ":" + hit.line, false)
                ImGui.tableNextColumn()
                ImGui.text(if (hit.line > 0) "${hit.line + 1}" else "")
                ImGui.tableNextColumn()
                ImGui.textColored(ImVec4(0.7f, 0.8f, 1f, 1f), hit.lineText.ifEmpty { " " })
                if (selected) core.openSearchHit(hit)
            }
            ImGui.endTable()
        }

        ImGui.end()

        // remember the mode for change detection on the next frame
        modeChanged = false
    }

    private var scopeDir: String? = null

    /** Programmatic trigger (menu / shortcut) scoped to [dir]. */
    fun show(dir: String?) {
        scopeDir = dir
        if (dir != null) searchRoot = dir
    }

    /** Truncates [text] with an ellipsis when it exceeds [maxWidth]. */
    private fun clipToWidth(text: String, maxWidth: Float): String {
        if (ImGui.calcTextSize(text).x <= maxWidth) return text
        val sb = StringBuilder()
        var w = 0f
        for (ch in text) {
            val cw = ImGui.calcTextSize(ch.toString()).x
            if (w + cw > maxWidth - 10f) break
            sb.append(ch)
            w += cw
        }
        return sb.toString() + "…"
    }

    private fun runSearch(root: String) {
        if (query.isBlank()) return
        var root0 = root
        if (root0.isBlank()) {
            // fall back to the user's home directory so the action is never a
            // silent no-op (search will still surface results for loose files)
            root0 = cn.enaium.imcode.platform.Platform.userHome
            if (root0.isBlank()) return
        }
        val mode = if (modeIndex == 0) SearchService.Mode.FILE_NAME else SearchService.Mode.CONTENT
        searchRoot = root0
        searching = true
        hits = emptyList()
        service.search(root0, mode, query)
        cn.enaium.imcode.app.OutputLog.append(
            "Search", cn.enaium.imcode.app.LogLevel.INFO,
            "search '${query}' (${if (mode == SearchService.Mode.FILE_NAME) "file names" else "content"}) in ${root0.takeLast(60)}",
        )
    }
}