package cn.enaium.imcode.ui

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiChildFlags
import cn.enaium.imgui.ImGuiWindowFlags
import cn.enaium.imgui.ImVec2
import cn.enaium.imcode.app.LogLevel
import cn.enaium.imcode.app.OutputLog
import cn.enaium.imcode.config.Config
import cn.enaium.lsp.edit.DocPos
import cn.enaium.lsp.edit.Editor
import cn.enaium.lsp.edit.EditorFoldRange
import cn.enaium.lsp.edit.EditorPalette
import cn.enaium.lsp.edit.JetBrainsThemes
import cn.enaium.lsp.edit.Language
import cn.enaium.lsp.edit.SyntaxHighlighter

/**
 * Bottom output panel: "Log" (app/LSP-process logs) + "LSP" RPC frames.
 *
 * Both tabs render through an lsp-edit `Editor`, so selection, copy, scrolling
 * and theming are the ones the code editors already have. Editing, line
 * numbers, the minimap and the find/replace bar are off: a log is read, not
 * edited, and its own search bar would only cover the text.
 *
 * The LSP tab is a foldable document: every frame is one summary line
 * (`[23:58:19] Sending request 'initialize - (1)'.`) with its payload folded
 * under it, so the console opens as a list of frames and expands on click.
 */
class OutputPanel(private val config: () -> Config) {

    private var stickyBottom = BooleanArray(1) { true }
    private var selectedTab = 0
    private var lastRevision = -1
    private val maxLines = 800

    /** Log tab: the editor and the revision it was last filled from. */
    private var logEditor: Editor? = null
    private var logRevision = -1
    private var logWasLsp = false

    /** LSP tab: the same, plus the fold ranges its document carries. */
    private var lspEditor: Editor? = null

    /** Buffer lines of the current LSP document that hold JSON payloads. */
    private var lspJsonLines: Set<Int> = emptySet()

    /** Entry seqs whose frame the console has already collapsed once. */
    private val collapsedFrames = HashSet<Long>()

    /** Entry seqs the user expanded: the console never re-collapses these. */
    private val expandedFrames = HashSet<Long>()
    private var lspRevision = -1
    private var lspWasLsp = false

    fun draw() {
        val avail = ImGui.getContentRegionAvail()
        val tabH = ImGui.getFrameHeight()
        val bodyH = (avail.y - tabH - 4f).coerceAtLeast(40f)

        // ---- tab row: fixed (never scrolls with the entries) ----
        if (ImGui.beginChild("##out-tabbar", ImVec2(avail.x, tabH), 0,
                ImGuiWindowFlags.NO_SCROLLBAR or ImGuiWindowFlags.NO_SCROLL_WITH_MOUSE)) {
            if (ImGui.beginTabBar("##output-tabs", 0)) {
                if (ImGui.beginTabItem("Log")) { selectedTab = 0; ImGui.endTabItem() }
                if (ImGui.beginTabItem("LSP")) { selectedTab = 1; ImGui.endTabItem() }
                ImGui.endTabBar()
            }
        }
        ImGui.endChild()

        if (ImGui.beginChild("##out-body", ImVec2(avail.x, bodyH), ImGuiChildFlags.BORDERS)) {
            if (selectedTab == 1) drawLspEntries() else drawLogEntries()
        }
        ImGui.endChild()
    }

    /** Entries of the selected tab, oldest first, bounded by [maxLines]. */
    private fun entries(showLsp: Boolean): List<OutputLog.Entry> {
        val all = OutputLog.snapshot()
        val filtered = if (showLsp) {
            all.filter { it.level == LogLevel.LSP || it.level == LogLevel.SERVER }
        } else {
            all.filter { it.level != LogLevel.LSP && it.level != LogLevel.SERVER }
        }
        return if (filtered.size > maxLines) filtered.subList(filtered.size - maxLines, filtered.size) else filtered
    }

    private fun toolbar(entries: List<OutputLog.Entry>, showLsp: Boolean) {
        ImGui.textDisabled("${entries.size} entries")
        ImGui.sameLine()
        if (ImGui.smallButton("Clear")) OutputLog.clear()
        ImGui.sameLine()
        ImGui.checkbox("Auto-scroll", stickyBottom)
        if (showLsp && !config().rpcLogging) {
            ImGui.sameLine()
            ImGui.textDisabled("(RPC frames disabled - server logs still shown)")
        }
        ImGui.separator()
    }

    // ==================== Log tab ====================

    private fun drawLogEntries() {
        val entries = entries(showLsp = false)
        toolbar(entries, showLsp = false)
        val ed = editor(logEditor) { created ->
            // App log lines are plain text: the tokenizer would only colour
            // the quotes and digits inside them.
            created.tokenProvider = { emptyList() }
            logEditor = created
        }
        val revision = OutputLog.revision
        if (revision != logRevision || logWasLsp) {
            val atBottom = stickyBottom[0]
            ed.setText(entries.joinToString("\n") { it.text })
            logRevision = revision
            logWasLsp = false
            // Following the cursor is how the editor scrolls; the caret only
            // moves when the log is meant to follow its tail.
            if (atBottom && ed.lineCount() > 0) ed.setCursor(DocPos(ed.lineCount() - 1, 0))
        }
        ed.render("##log-body", ImVec2(-1f, -1f))
    }

    // ==================== LSP tab ====================

    private fun drawLspEntries() {
        val entries = entries(showLsp = true)
        toolbar(entries, showLsp = true)
        val ed = editor(lspEditor) { created -> lspEditor = created }
        val revision = OutputLog.revision
        if (revision != lspRevision || !lspWasLsp) {
            val document = buildLspDocument(entries)
            lspJsonLines = document.jsonLines
            ed.setText(document.text)
            ed.setFoldRanges(document.folds)
            applyFrameFolds(ed, document, collapsedFrames, expandedFrames)
            lspRevision = revision
            lspWasLsp = true
        }
        ed.render("##lsp-body", ImVec2(-1f, -1f))
    }


/** One frame's summary line plus the payload folded under it. */
    internal class LspDocument(
        val text: String,
        val folds: List<EditorFoldRange>,
        /** Buffer lines holding a frame's JSON payload. */
        val jsonLines: Set<Int>,
    )

    /**
     * The LSP tab's document: one line per frame, its payload indented below,
     * and a fold range over each payload so the console opens as a list of
     * frames.
     *
     * A frame's payload arrives as indented follow-up entries from the
     * transport, which is what makes the grouping possible without the log
     * having to carry structure.
     */
    internal fun buildLspDocument(entries: List<OutputLog.Entry>): LspDocument {
        val text = StringBuilder()
        val folds = ArrayList<EditorFoldRange>()
        val jsonLines = HashSet<Int>()
        var line = 0
        var index = 0
        for (entry in entries) {
            val start = line
            // The tag names the server: the tab collects every running one.
            val head = "[${entry.tag}] "
            val parts = entry.text.split('\n')
            for ((i, part) in parts.withIndex()) {
                text.append(if (i == 0) head + part else part).append('\n')
                line++
            }
            // The frame and its payload are one entry, so the fold covers
            // everything after the first line. Its marker is the first line of
            // what is hidden — the payload's own opening line — so a closed
            // frame shows the summary and a taste of the payload, and the
            // marker never repeats the line above it.
            // LSP's collapsedText is what the row shows while the fold is
            // closed: the frame's own summary line, with the payload under it.
            if (parts.size > 1) {
                // The entry's seq keys the fold's collapse state: the line
                // numbers move as old entries slide out of the window, the seq
                // does not.
                folds += EditorFoldRange(start, line - 1, collapsedText = head + parts.first(), key = entry.seq)
                for (payloadLine in start + 1 until line) jsonLines += payloadLine
            }
        }
        return LspDocument(text.toString(), folds, jsonLines)
    }

    // ==================== the editors ====================

    /**
     * The editor for one tab, created on first use: constructing it needs a
     * frame and this window's ImGui context.
     */
    private inline fun editor(current: Editor?, store: (Editor) -> Unit): Editor {
        current?.let { existing ->
            existing.palette = palette()
            return existing
        }
        // No keywords to colour: a log line is not code. The rest of the
        // language stays at its defaults — an empty numberChars/string set
        // makes the tokenizer's scanners unable to advance.
        val created = Editor(initialText = "", language = Language(name = "log", keywords = emptySet()))
        created.readOnly = true
        created.showLineNumbers = false
        created.showMinimap = false
        // The frame lines are plain log text, but the payloads folded under
        // them are JSON: only those lines are tokenized as JSON, so a
        // timestamp's digits or a quoted word in a summary stay plain while
        // the payload — and its fold preview — gets real syntax colors.
        created.tokenProvider = { line ->
            if (line in lspJsonLines) {
                val text = lspEditor?.buffer?.line(line)
                if (text != null) SyntaxHighlighter.tokenize(Language.json, text, 0).spans else emptyList()
            } else {
                emptyList()
            }
        }
        created.palette = palette()
        store(created)
        return created
    }

    /** The workspace theme's palette, or lsp-edit's default when unset. */
    private fun palette(): Array<Long> {
        val name = config().editor.theme
        return if (name.isEmpty()) EditorPalette.dark else JetBrainsThemes.all.firstOrNull { it.first == name }?.second
            ?: EditorPalette.dark
    }
}

/**
 * Collapses the frames of [document] that the console has not collapsed yet,
 * leaving the frames the user expanded alone. [collapsedFrames] and
 * [expandedFrames] carry the state across rebuilds.
 *
 * Every frame is collapsed exactly once, so the console opens as a list of
 * frames and stays one while the log keeps arriving. Toggling on every rebuild
 * instead flipped the whole console on each new entry — half the frames showed
 * unfolded — and the state is kept against the entry's seq (the fold's key)
 * because an old entry sliding out of the window shifts every line number
 * under it.
 */
internal fun applyFrameFolds(
    editor: Editor,
    document: OutputPanel.LspDocument,
    collapsedFrames: MutableSet<Long>,
    expandedFrames: MutableSet<Long>,
) {
    for (fold in document.folds) {
        val seq = fold.key ?: continue
        if (seq in expandedFrames) continue
        if (editor.isLineFolded(fold.startLine)) {
            collapsedFrames += seq
        } else if (seq in collapsedFrames) {
            expandedFrames += seq
        } else {
            editor.toggleFold(fold.startLine)
            collapsedFrames += seq
        }
    }
}
