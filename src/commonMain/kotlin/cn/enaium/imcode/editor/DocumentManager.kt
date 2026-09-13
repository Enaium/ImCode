package cn.enaium.imcode.editor

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiKey
import cn.enaium.imgui.ImVec2
import cn.enaium.lsp.edit.DocPos
import cn.enaium.lsp.edit.EditOp
import cn.enaium.lsp.edit.Editor
import cn.enaium.lsp.edit.EditorEdit
import cn.enaium.lsp.edit.EditorInlayHint
import cn.enaium.lsp.edit.EditorCodeLens
import cn.enaium.lsp.edit.EditorFoldRange
import cn.enaium.lsp.edit.EditorMarker
import cn.enaium.lsp.edit.EditorPalette
import cn.enaium.lsp.edit.JetBrainsThemes
import cn.enaium.lsp.edit.PaletteIndex
import cn.enaium.lsp.edit.TokenSpan
import cn.enaium.lsp.model.CodeLens
import cn.enaium.lsp.model.Command
import cn.enaium.lsp.model.Diagnostic
import cn.enaium.lsp.model.DiagnosticCode
import cn.enaium.lsp.model.DiagnosticSeverity
import cn.enaium.lsp.model.DocumentSymbol
import cn.enaium.lsp.model.Location
import cn.enaium.lsp.model.Position
import cn.enaium.lsp.model.TextEdit
import cn.enaium.imcode.app.Mailbox
import cn.enaium.imcode.app.OutputLog
import cn.enaium.imcode.app.LogLevel
import cn.enaium.imcode.config.Config
import cn.enaium.imcode.lsp.LspManager
import cn.enaium.imcode.platform.ioFile
import cn.enaium.imcode.platform.isArchiveEntryPath
import cn.enaium.imcode.platform.readFileBytes
import cn.enaium.imcode.util.LanguageDetect
import cn.enaium.imcode.util.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

var AutoDebug = false

/**
 * Owns every open [Document]: creation, editor wiring, LSP document sync
 * (via [LspManager]), diagnostics markers, save/close lifecycle and the
 * LSP-driven UI data (symbols, code lenses). All public methods run on the
 * render thread.
 */
class DocumentManager(
    private val config: () -> Config,
    private val mailbox: Mailbox,
    internal val lsp: LspManager,
    private val onDocumentOpened: (Document) -> Unit,
    private val onDocumentClosed: (Document) -> Unit,
    private val onNavigate: (path: String, line: Int, index: Int) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serial dispatcher for tree-sitter parsing (the parser is not thread safe). */
    private val tsDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val docs = LinkedHashMap<String, Document>()



    internal fun docFor(path: String): Document? = docs[path]

    internal fun registerDocForTest(doc: Document) {
        docs[doc.path] = doc
    }

    /** Path -> Document for every open file (render thread). */
    val openDocs: List<Document> get() = docs.values.toList()

    fun get(path: String): Document? = docs[path]
    fun isOpen(path: String): Boolean = docs.containsKey(path)

    // ==================== open / create ====================

    /**
     * Opens [path] (async read, editor created on the render thread).
     */
    fun open(path: String, workspaceDir: String?, jumpTo: Pair<Int, Int>? = null) {
        val existing = docs[path]
        if (existing != null) {
            if (jumpTo != null) navigate(existing, jumpTo.first, jumpTo.second)
            return
        }
        val f = ioFile(path)
        val targetDir = workspaceDir ?: runCatching { f.parent?.absolutePath }.getOrNull()
        scope.launch {
            val bytes = try {
                readFileBytes(path)
            } catch (t: Throwable) {
                mailbox.post {
                    OutputLog.error("Editor", "failed to read $path: ${t.message}")
                }
                return@launch
            }
            val binary = isBinaryContent(bytes)
            val content = if (binary) "" else String(bytes, Charsets.UTF_8)
            mailbox.post {
                if (!docs.containsKey(path)) {
                    val doc = createDocument(path, targetDir, content)
                    doc.isBinary = binary
                    doc.binaryBytes = bytes
                    if (binary) {
                        doc.hexEditor =
                            cn.enaium.imgui.extensions.memoryeditor.MemoryEditor.create().also { me ->
                                cn.enaium.imgui.extensions.memoryeditor.MemoryEditor.setReadOnly(me, true)
                                cn.enaium.imgui.extensions.memoryeditor.MemoryEditor.setOptShowOptions(me, false)
                            }
                    }
                    docs[path] = doc
                    onDocumentOpened(doc)
                    lsp.syncLifecycle(openDocs)
                    if (jumpTo != null) navigate(doc, jumpTo.first, jumpTo.second)
                }
            }
        }
    }

    /**
     * Heuristic text/binary detection: a file is binary when it contains a
     * NUL byte or a high fraction of non-text bytes in the first 8 KiB.
     */
    internal fun isBinaryContent(bytes: ByteArray): Boolean {
        val sample = bytes.take(minOf(8192, bytes.size))
        if (sample.isEmpty()) return false
        var suspicious = 0
        for (b in sample) {
            if (b == 0.toByte()) return true
            val u = b.toInt() and 0xFF
                        // Control bytes other than newline/tab/CR are suspicious.
            if (u < 0x20 && u !in intArrayOf(9, 10, 13, 12, 8)) suspicious++
        }
        return suspicious > sample.size / 10
    }

    private fun createDocument(path: String, workspaceDir: String?, content: String): Document {
        val lang = LanguageDetect.detect(path)
        val editor = Editor(initialText = content)
        editor.uniqueId = path.hashCode().toLong() and 0x7FFFFFFF
        editor.language = lang.language
        editor.tabSize = config().editor.tabSize
        editor.showLineNumbers = config().editor.showLineNumbers
        editor.showMinimap = config().editor.showMinimap
        editor.wrapEnabled = config().editor.wordWrap
        themePalette(config().editor.theme)?.let { editor.palette = it }
        // Dependency sources opened from a jar: viewing/hovering is useful,
        // writing the entry back is impossible — make the buffer read-only
        // instead of failing on save (or worse, truncating the jar).
        if (isArchiveEntryPath(path)) editor.readOnly = true

        val doc = Document(
            path = path,
            workspaceDir = workspaceDir,
            uri = Uri.pathToUri(path),
            languageId = lang.languageId,
            editor = editor,
        )
        wireHighlighting(doc)
        wireCallbacks(doc)
        return doc
    }

    fun navigate(doc: Document, line: Int, index: Int) {
        val l = line.coerceAtLeast(0)
        val i = index.coerceAtLeast(0)
        doc.editor.setCursor(DocPos(l, i))
    }

    // ==================== highlighting ====================

    /**
     * Decides how [doc] gets its syntax colors:
     *  - a configured LSP server matches the file -> NO tree-sitter (the
     *    server owns highlighting, semantic tokens or plain text);
     *  - otherwise, when the file extension has a tree-sitter grammar ->
     *    tree-sitter highlighting;
     *  - otherwise no highlighting at all (token provider returns empty).
     */
    private fun wireHighlighting(doc: Document) {
        val editor = doc.editor
        val spec = TreeSitterLang.forPath(doc.path)
        val highlighter = spec?.highlighter()?.also { it.setText(doc.text()) }
        doc.treeSitter = highlighter
        if (lsp.hasMatchingServer(doc)) {
            // LSP owns the file: the server's semantic tokens overlay the
            // tree-sitter base. Semantic tokens alone cover only what the
            // server reports (keywords, symbols), leaving most code plain,
            // so tree-sitter stays as the base layer underneath.
            editor.tokenProvider = { line -> mergedSpansFor(doc, line) }
            requestSemanticTokens(doc)
            return
        }
        if (highlighter != null) {
            editor.tokenProvider = { line -> highlighter.spansForLine(line) }
        } else {
            // No grammar for this file type: render without highlighting.
            editor.tokenProvider = { null }
        }
    }

    /** Tree-sitter base + semantic overlay for [line]. */
    private fun mergedSpansFor(doc: Document, line: Int): List<TokenSpan>? {
        val base = doc.treeSitter?.spansForLine(line)
        val overlay = doc.semanticSpans?.get(line)
        if (overlay.isNullOrEmpty()) return base
        if (base.isNullOrEmpty()) return overlay
        return mergeSpans(base, overlay)
    }

    /**
     * Overlays [overlay] on [base]: both are split at every boundary and
     * each sub-range takes the overlay's color when covered by it, the
     * base's otherwise (empty when neither covers it).
     */
    internal fun mergeSpans(base: List<TokenSpan>, overlay: List<TokenSpan>): List<TokenSpan> {
        val points = sortedSetOf<Int>()
        for (s in base) { points.add(s.start); points.add(s.end) }
        for (s in overlay) { points.add(s.start); points.add(s.end) }
        val bounds = points.toList()
        val out = ArrayList<TokenSpan>()
        for (i in 0 until bounds.size - 1) {
            val a = bounds[i]
            val b = bounds[i + 1]
            if (b <= a) continue
            val ov = overlay.firstOrNull { a >= it.start && b <= it.end }
            if (ov != null) {
                out.add(TokenSpan(a, b, ov.palette))
                continue
            }
            val bs = base.firstOrNull { a >= it.start && b <= it.end }
            if (bs != null) out.add(TokenSpan(a, b, bs.palette))
        }
        return out
    }

    /**
     * Re-requests semantic tokens for every open document owned by a
     * language server. Called when a server becomes ready: documents opened
     * before the handshake finished got no tokens (the request bailed out
     * while the client was still starting), which left LSP-owned files
     * unhighlighted until the next edit.
     */
    fun refreshLspHighlighting() {
        for (doc in openDocs) {
            if (!doc.isBinary) requestSemanticTokens(doc)
        }
    }

    /**
     * Requests full-document semantic tokens from the server that owns
     * [doc]; decodes them into per-line spans and installs them as the
     * editor's token provider. No-op when the server does not advertise
     * semantic tokens.
     */
    private fun requestSemanticTokens(doc: Document) {
        val client = lsp.matchingClient(doc) ?: return
        if (!client.semanticSupported) return
        val legend = client.semanticLegend ?: return
        lsp.requestSemanticTokensFull(doc) { tokens ->
            if (tokens == null || !docs.containsKey(doc.path)) return@requestSemanticTokensFull
            doc.semanticSpans = decodeSemanticTokens(tokens, legend)
            doc.editor.invalidateAll()
        }
    }

    private fun decodeSemanticTokens(
        tokens: cn.enaium.lsp.model.SemanticTokens,
        legend: cn.enaium.lsp.model.SemanticTokensLegend,
    ): Map<Int, List<TokenSpan>> {
        val result = HashMap<Int, MutableList<TokenSpan>>()
        var line = 0
        var startChar = 0
        var i = 0
        val data = tokens.data
        while (i + 4 <= data.size) {
            val deltaLine = data[i]
            val deltaStart = data[i + 1]
            val length = data[i + 2]
            val tokenType = data[i + 3]
            i += 5
            line += deltaLine
            if (deltaLine == 0) startChar += deltaStart else startChar = deltaStart
            val palette = mapTokenType(tokenType, legend.tokenTypes)
            if (length > 0) {
                result.getOrPut(line) { mutableListOf() }
                    .add(TokenSpan(startChar, startChar + length, palette))
            }
            // LSP spec: deltaStart is relative to the previous token's
            // START (a gap between starts), so startChar must not advance
            // by the token length — doing so shifted every later token.
        }
        return result
    }

    private fun mapTokenType(type: Int, legend: List<String>): Int {
        if (type !in legend.indices) return PaletteIndex.TEXT
        return when (legend[type]) {
            "keyword" -> PaletteIndex.KEYWORD
            "comment" -> PaletteIndex.COMMENT
            "string" -> PaletteIndex.STRING
            "number" -> PaletteIndex.NUMBER
            "type", "class", "enum", "interface", "struct", "namespace" -> PaletteIndex.KNOWN_IDENTIFIER
            "function", "method", "macro" -> PaletteIndex.DECLARATION
            "operator" -> PaletteIndex.PUNCTUATION
            "preprocessor" -> PaletteIndex.PREPROCESSOR
            else -> PaletteIndex.TEXT
        }
    }

    /** Re-highlights a document after external text replacement. */
    fun rehighlight(doc: Document) {
        doc.treeSitter?.setText(doc.text())
        doc.editor.invalidateAll()
    }

    private val rehighlightJobs = HashMap<String, Job>()

    private val completionJobs = HashMap<String, Job>()

    /**
     * Closes the completion popup when the identifier prefix before the
     * caret becomes empty (the user deleted what they typed) — otherwise
     * the list stayed open over an empty prefix.
     */
    private fun dismissCompletionIfPrefixEmpty(doc: Document, ops: List<EditOp>) {
        if (!doc.completionActive) return
        if (ops.none { !it.insert }) return
        val pos = doc.editor.cursor
        val line = doc.editor.buffer.line(pos.line)
        var start = pos.index.coerceIn(0, line.length)
        while (start > 0) {
            val c = line[start - 1]
            if (c.isLetterOrDigit() || c == '_') start-- else break
        }
        if (start == pos.index) {
            doc.completionActive = false
            doc.completionItems = emptyList()
        }
    }

    /**
     * Asks the server for completion after typing an identifier character or
     * a server-declared trigger character ('.', ':' …). Debounced: the
     * request fires once the typing pauses briefly, and a stale in-flight
     * request is simply superseded by the next one.
     */
    private fun scheduleAutoCompletion(doc: Document, ops: List<EditOp>) {
        if (doc.suppressAutoCompletion) return
        val last = ops.lastOrNull() ?: return
        if (!last.insert || last.text.isEmpty()) return
        // Only single-character typing opens the popup: pasting or IME
        // composition (multi-char ops) must not.
        if (last.text.length > 1) return
        val c = last.text.last()
        val triggers = lsp.triggerCharactersFor(doc)
        val shouldTrigger = c.isLetterOrDigit() || c == '_' || triggers.contains(c.toString())
        if (!shouldTrigger) return
        completionJobs.remove(doc.path)?.cancel()
        completionJobs[doc.path] = scope.launch {
            delay(150)
            mailbox.post {
                if (docs.containsKey(doc.path)) invokeCompletion(doc)
            }
        }
    }

    /**
     * Debounced tree-sitter re-parse after edits: parsing the whole tree on
     * every keystroke stalls the render thread for large files, so a quiet
     * gap of 400 ms triggers one re-parse. Runs on the IO dispatcher; the
     * highlighter is only touched from this single job at a time.
     */
    private fun scheduleRehighlight(doc: Document) {
        val ts = doc.treeSitter
        // Semantic spans carry OLD coordinates; keeping them while the text
        // changes paints highlights at wrong offsets until the debounced
        // refresh lands. Drop them immediately (the tree-sitter base takes
        // over until the server answers).
        doc.semanticSpans = null
        // Snapshot on the render thread; the highlighter is only touched on
        // the serial tree-sitter dispatcher (background), so parsing never
        // stalls the UI and two parses never overlap.
        val snapshot = doc.text()
        rehighlightJobs.remove(doc.path)?.cancel()
        rehighlightJobs[doc.path] = scope.launch(tsDispatcher) {
            if (ts != null) {
                ts.setText(snapshot)
                mailbox.post {
                    if (docs.containsKey(doc.path)) doc.editor.invalidateAll()
                }
            }
            // Semantic tokens cost a server round trip: keep the quiet-gap
            // debounce so an edit storm does not flood the server.
            delay(400)
            mailbox.post {
                if (docs.containsKey(doc.path) && lsp.hasMatchingServer(doc)) {
                    requestSemanticTokens(doc)
                }
            }
        }
    }

    // ==================== editor wiring ====================

    private fun wireCallbacks(doc: Document) {
        val editor = doc.editor

        editor.onTextChange = { ops ->
            if (ops.isNotEmpty()) {
                doc.dirty = doc.text() != doc.savedText
                doc.version++
                doc.hoverCache.clear()
                // Close any open hover tooltip: while typing it would hang
                // over the text and hide what is being written.
                doc.hoverText = null
                doc.hoverPosKey = null
                doc.hoverActive = false
                // Incremental LSP sync.
                lsp.documentChanged(doc, ops)
                scheduleRehighlight(doc)
                scheduleAutoCompletion(doc, ops)
                dismissCompletionIfPrefixEmpty(doc, ops)
            }
        }

        editor.onHover = { pos ->
            doc.hoverActive = true
            handleHover(doc, pos.line, pos.index)
        }
        editor.onHoverEnd = {
            // Do not drop the text here: the hover popup keeps it alive
            // while the pointer rests on the popup itself. WorkspaceWindow
            // clears it once neither side wants it (it owns that state).
            doc.hoverActive = false
            doc.hoverLoading = false
        }

        editor.onCodeLensClick = { lens -> onCodeLensClicked(doc, lens) }

        // While the completion popup is open, arrows/Enter/Tab belong to it.
        editor.keysReservedByOverlay = { doc.completionActive }

        // LSP actions appended to the editor's context menu. The host
        // (workspace window) supplies the UI actions via the callbacks.
        editor.onContextMenu = {
            if (lsp.hasMatchingServer(doc)) {
                ImGui.separator()
                if (ImGui.menuItem("Go to Definition", "F12")) {
                    requestDefinition(doc)
                }
                if (ImGui.menuItem("Find References", "Shift+F12")) {
                    onFindReferences?.invoke(doc)
                }
                if (ImGui.menuItem("Rename...", "F2")) {
                    onRenameRequested?.invoke(doc)
                }
                if (ImGui.menuItem("Show Code Actions...", "Ctrl+.")) {
                    onCodeActionsRequested?.invoke(doc)
                }
            }
        }
    }

    /** Host hook: open the references panel for [doc] (context menu). */
    var onFindReferences: ((Document) -> Unit)? = null

    /** Host hook: open the rename dialog for [doc] (context menu). */
    var onRenameRequested: ((Document) -> Unit)? = null

    /** Host hook: open the code-actions list for [doc] (context menu). */
    var onCodeActionsRequested: ((Document) -> Unit)? = null

    private fun onCodeLensClicked(doc: Document, lens: EditorCodeLens) {
        val raw = doc.codeLenses[lens.line]?.firstOrNull { it.command?.title == lens.title }
        val command = raw?.command
        if (command != null) {
            executeCommand(doc, command)
        }
    }

    /** Executes a code lens [Command]; host hook for runnable lenses. */
    var onExecuteCommand: ((Document, Command) -> Unit)? = null

    private fun executeCommand(doc: Document, command: Command) {
        onExecuteCommand?.invoke(doc, command)
    }

    private fun handleHover(doc: Document, line: Int, index: Int) {
        val key = "$line:$index"
        if (doc.hoverCache.containsKey(key)) {
            val cached = doc.hoverCache[key]
            doc.hoverPosKey = key
            doc.hoverText = cached
            doc.hoverLoading = false
            return
        }
        if (doc.hoverPosKey == key) return // request already in flight
        doc.hoverPosKey = key
        doc.hoverText = null
        doc.hoverLoading = true
        lsp.requestHover(doc, Position(line, index)) { text ->
            if (doc.hoverPosKey == key) {
                doc.hoverCache[key] = text
                doc.hoverText = text
                doc.hoverLoading = false
            }
        }
    }

    // ==================== LSP data (symbols / code lenses) ====================

    /** Sets the document symbol tree (from the structure panel refresh). */
    fun setDocumentSymbols(doc: Document, symbols: List<DocumentSymbol>) {
        doc.symbols = symbols
    }

    /** Sets code lenses; wires them into the editor's lens provider. */
    fun setCodeLenses(doc: Document, lenses: List<CodeLens>) {
        doc.codeLenses = lenses
            .filter { it.command?.title != null }
            .groupBy { it.range.start.line }
        doc.editor.codeLensProvider = { line ->
            doc.codeLenses[line]?.map {
                EditorCodeLens(line, it.command!!.title, it.command?.command)
            }
        }
        doc.editor.invalidateAll()
    }

    /** Replaces the document's inlay hints (textDocument/inlayHint). */
    fun setInlayHints(doc: Document, hints: List<cn.enaium.lsp.model.InlayHint>) {
        doc.inlayHints = hints.groupBy { it.position.line }
        doc.editor.inlayHintsProvider = { line ->
            doc.inlayHints[line]?.mapNotNull { hint ->
                val label = inlayLabelText(hint.label) ?: return@mapNotNull null
                EditorInlayHint(DocPos(hint.position.line, hint.position.character), label)
            }
        }
        doc.editor.invalidateAll()
    }

    /** Plain string label or the concatenated parts of an inlay hint. */
    private fun inlayLabelText(label: cn.enaium.lsp.model.InlayHintLabel): String? = when (label) {
        is cn.enaium.lsp.model.InlayHintLabel.StringValue -> label.value
        is cn.enaium.lsp.model.InlayHintLabel.Parts -> label.value.joinToString("") { it.value }
    }

    /** Replaces the document's fold ranges (textDocument/foldingRange). */
    fun setFoldRanges(doc: Document, ranges: List<cn.enaium.lsp.model.FoldingRange>) {
        doc.editor.setFoldRanges(
            ranges.mapNotNull { r ->
                // LSP: endCharacter == 0 means the end line is not part of the
                // fold (it ends at the previous line). lsp-edit hides
                // startLine+1..endLine, so single-line results are dropped.
                val last = if (r.endCharacter == 0 && r.endLine > r.startLine) r.endLine - 1 else r.endLine
                if (last > r.startLine) EditorFoldRange(r.startLine, last) else null
            },
        )
        doc.editor.invalidateAll()
    }

    // ==================== diagnostics ====================

    fun applyDiagnostics(doc: Document, diagnostics: List<Diagnostic>) {
        doc.diagnostics = diagnostics
        val byLine = LinkedHashMap<Int, MutableList<DiagInfo>>()
        val rangesByLine = LinkedHashMap<Int, MutableList<Pair<Int, Int>>>()
        var errors = 0
        var warnings = 0
        for (d in diagnostics) {
            val severity = d.severity ?: DiagnosticSeverity.Error
            when (severity) {
                DiagnosticSeverity.Error -> errors++
                DiagnosticSeverity.Warning -> warnings++
            }
            val line = d.range.start.line
            byLine.getOrPut(line) { ArrayList() }
                .add(DiagInfo(severity, diagnosticText(d)))
            // Underline exactly the reported span (multi-line diagnostics
            // underline to the end of their first line).
            val start = d.range.start.character
            val end = if (d.range.end.line == line) d.range.end.character else Int.MAX_VALUE
            rangesByLine.getOrPut(line) { ArrayList() }.add(start to end)
        }
        doc.diagByLine = byLine
        doc.errorCount = errors
        doc.warningCount = warnings

        if (!docs.containsKey(doc.path)) return
        val editor = doc.editor
        editor.markers.clear()
        for ((line, infos) in byLine) {
            val worst = infos.minByOrNull { it.severity } ?: continue
            val (lineNumberColor, squiggleColor) = markerColors(worst.severity)
            val tooltip = infos.joinToString("\n") { it.message }
            editor.markers[line] = EditorMarker(
                lineNumberColor = lineNumberColor,
                underlineColor = squiggleColor,
                underlineRanges = rangesByLine[line],
                lineNumberTooltip = tooltip,
                textTooltip = tooltip,
            )
        }
    }

    /**
     * Per-severity gutter color + squiggle color. Errors get a red wave,
     * warnings a yellow one; the line text itself is NOT recolored (an
     * all-white tint made errors and warnings look identical).
     */
    private fun markerColors(severity: Int): Pair<Long, Long> {
        return when (severity) {
            DiagnosticSeverity.Error -> 0xFFFF5555L to 0xFFFF4040L
            DiagnosticSeverity.Warning -> 0xFFFFAA00L to 0xFFFFCC00L
            DiagnosticSeverity.Information -> 0xFF55AAFFL to 0xFF55AAFFL
            else -> 0xFFAAAAAAL to 0xFFAAAAAAL
        }
    }

    private fun diagnosticText(d: Diagnostic): String {
        val message = when (val m = d.message) {
            is cn.enaium.lsp.model.Documentation.StringValue -> m.value
            is cn.enaium.lsp.model.Documentation.Markup -> m.value.value
            null -> ""
        }
        if (message.isNotBlank()) return message
        return when (val c = d.code) {
            is DiagnosticCode.StringValue -> c.value
            is DiagnosticCode.NumberValue -> c.value.toString()
            null -> "diagnostic"
        }
    }

    // ==================== LSP context actions ====================

    /** Go to definition for the word under the caret. */
    fun requestDefinition(doc: Document) {
        val pos = doc.editor.cursor
        lsp.requestDefinition(doc, Position(pos.line, pos.index)) { loc ->
            if (loc != null) {
                onNavigate(
                    Uri.uriToPath(loc.uri) ?: loc.uri,
                    loc.range.start.line,
                    loc.range.start.character,
                )
            }
        }
    }

    // ==================== save ====================

    fun save(doc: Document) {
        if (isArchiveEntryPath(doc.path)) {
            OutputLog.warn("Editor", "not saving archive entry ${doc.path}")
            return
        }
        val text = doc.text()
        scope.launch {
            try {
                ioFile(doc.path).writeText(text)
                mailbox.post {
                    if (docs.containsKey(doc.path)) {
                        doc.savedText = text
                        doc.dirty = false
                        lsp.documentSaved(doc, text)
                        OutputLog.info("Editor", "saved ${doc.path}")
                    }
                }
            } catch (t: Throwable) {
                OutputLog.error("Editor", "save failed ${doc.path}: ${t.message}")
            }
        }
    }

    fun saveAll() {
        docs.values.filter { it.dirty }.forEach { save(it) }
    }

    /** Applies editor preferences to every open document. */
    fun applyEditorPrefs() {
        val cfg = config().editor
        val palette = themePalette(cfg.theme)
        for (doc in docs.values) {
            doc.editor.tabSize = cfg.tabSize
            doc.editor.showLineNumbers = cfg.showLineNumbers
            doc.editor.showMinimap = cfg.showMinimap
            doc.editor.wrapEnabled = cfg.wordWrap
            doc.editor.palette = palette ?: EditorPalette.dark
        }
    }

    /**
     * Palette for a `JetBrainsThemes.all` name, or null to keep lsp-edit's
     * built-in Dark palette (empty/unknown names).
     */
    private fun themePalette(name: String): Array<Long>? =
        if (name.isEmpty()) null else JetBrainsThemes.all.firstOrNull { it.first == name }?.second

    // ==================== new file ====================

    fun createFile(dir: String, name: String, onCreated: (String) -> Unit) {
        val clean = name.trim().trimStart('/').trimStart('\\')
        if (clean.isEmpty()) return
        val target = ioFile(dir + "/" + clean)
        scope.launch {
            try {
                if (!target.exists) target.createNewFile()
                mailbox.post { onCreated(target.absolutePath) }
            } catch (t: Throwable) {
                OutputLog.error("Editor", "create failed ${target.absolutePath}: ${t.message}")
            }
        }
    }

    // ==================== close ====================

    fun close(doc: Document) {
        if (!docs.containsKey(doc.path)) return
        docs.remove(doc.path)
        lsp.documentClosed(doc)
        doc.close()
        lsp.syncLifecycle(openDocs)
    }


    // ==================== completion (host-rendered popup) ====================

    /**
     * Requests completion at the caret and stores the rows on [doc].
     * The popup itself is rendered by the workspace window.
     */
    fun invokeCompletion(doc: Document, triggerChar: String? = null) {
        if (doc.completionInFlight) return
        doc.completionInFlight = true
        val pos = doc.editor.cursor
        lsp.requestCompletion(doc, Position(pos.line, pos.index), triggerChar) { items ->
            // Response size logged like documentSymbol's: useful when a
            // server returns nothing.
            OutputLog.append("LSP", LogLevel.LSP, "completion: ${items.size} item(s)")
            doc.completionInFlight = false
            doc.completionItems = rankedItems("", items)
            doc.completionSelected = 0
            doc.completionActive = doc.completionItems.isNotEmpty()
        }
    }

    /** Diagnostic/test hook: fill and open the popup with [items]. */
    fun openCompletionForTest(doc: Document, items: List<cn.enaium.lsp.model.CompletionItem>) {
        doc.completionItems = rankedItems("", items)
        doc.completionSelected = 0
        doc.completionActive = doc.completionItems.isNotEmpty()
    }

    /** Ranks LSP completion items for the popup (server sortText order). */
    internal fun rankedItems(query: String, items: List<cn.enaium.lsp.model.CompletionItem>): List<CompletionRow> {
        if (items.isEmpty()) return emptyList()
        val q = query.lowercase()
        val nonKeyword = items.filter { it.kind != cn.enaium.lsp.model.CompletionItemKind.Keyword }
        val pool = nonKeyword.ifEmpty { items }
        val keyed = pool.mapIndexed { index, item ->
            Triple(item, item.sortText ?: index.toString().padStart(8, '0'), index)
        }
        val sorted = keyed
            .filter { (item, _, _) -> (item.filterText ?: item.label).lowercase().contains(q) }
            .sortedWith(compareBy({ it.first.preselect != true }, { it.second }, { it.third }))
            .map { it.first }
        return sorted
            .distinctBy { it.label + '\u0000' + (it.detail ?: "") }
            .take(48)
            .map { item ->
                // kotlin-lsp (JetBrains style) puts the type in
                // labelDetails.description ("Double", "Unit") and the
                // parameters/source in labelDetails.detail; item.detail is
                // null. Other servers may still use item.detail — a bare
                // identifier is treated as the type, a signature stays
                // inline.
                val ld = item.labelDetails
                val d = item.detail ?: ""
                val bareType = d.isNotEmpty() &&
                    d.none { it.isWhitespace() || it == '(' || it == ')' || it == ':' || it == ',' }
                CompletionRow(
                    label = item.label,
                    insert = editTextFor(item),
                    detail = ld?.detail ?: if (bareType) "" else d,
                    kind = item.kind,
                    doc = completionDoc(item),
                    trailing = ld?.description ?: if (bareType) d else "",
                    item = item,
                )
            }
    }

    private fun completionDoc(item: cn.enaium.lsp.model.CompletionItem): String =
        when (val d = item.documentation) {
            is cn.enaium.lsp.model.Documentation.StringValue -> d.value
            is cn.enaium.lsp.model.Documentation.Markup -> d.value.value
            null -> ""
        }

    private fun editTextFor(item: cn.enaium.lsp.model.CompletionItem): String {
        val t = when (val te = item.textEdit) {
            is cn.enaium.lsp.model.TextEditOrInsert.Edit -> te.value.newText
            is cn.enaium.lsp.model.TextEditOrInsert.InsertReplace -> te.value.newText
            null -> item.insertText
        } ?: item.label
        return stripSnippet(t)
    }

    private fun stripSnippet(text: String): String =
        text.replace(Regex("\\$\\$|\\$\\{\\d+:[^}]*\\}|\\$\\d+"), "")

    /**
     * Lazily resolves popup rows so the fields the server defers to
     * `completionItem/resolve` fill in while the list is visible (kotlin-lsp
     * defers documentation and completes labelDetails there). At most
     * [maxPerCall] new requests per call, each row at most once.
     */
    fun resolveCompletionRows(doc: Document, maxPerCall: Int = 3) {
        val client = lsp.matchingClient(doc) ?: return
        var budget = maxPerCall
        for (i in doc.completionItems.indices) {
            if (budget <= 0) break
            val row = doc.completionItems[i]
            val item = row.item ?: continue
            if (row.resolved || row.resolving) continue
            val marked = doc.completionItems.toMutableList()
            marked[i] = row.copy(resolving = true)
            doc.completionItems = marked
            budget--
            client.requestCompletionResolve(item) { resolved ->
                mailbox.post {
                    if (resolved == null) return@post
                    val idx = doc.completionItems.indexOfFirst { it.item === item }
                    if (idx < 0) return@post
                    val list = doc.completionItems.toMutableList()
                    val old = list[idx]
                    list[idx] = old.copy(
                        detail = resolved.labelDetails?.detail ?: old.detail,
                        trailing = resolved.labelDetails?.description ?: old.trailing,
                        doc = completionDoc(resolved).ifEmpty { old.doc },
                        item = resolved,
                        resolved = true,
                        resolving = false,
                    )
                    doc.completionItems = list
                }
            }
        }
    }

    /**
     * Accepts the completion at [index]. The item is resolved first when it
     * carries no additionalTextEdits yet (kotlin-lsp defers auto-imports to
     * `completionItem/resolve`), then the replacement and any additional
     * edits are applied in one go.
     */
    fun acceptCompletion(doc: Document, index: Int) {
        val row = doc.completionItems.getOrNull(index) ?: return
        // Never erase the typed prefix without inserting something: items
        // whose textEdit/insertText resolved to an empty string fall back
        // to their label (otherwise accepting made the text disappear).
        val insert = row.insert.ifEmpty { row.label }
        closeCompletion(doc)
        if (insert.isEmpty()) return
        val item = row.item
        val client = lsp.matchingClient(doc)
        // Resolve FIRST: kotlin-lsp (as a JetBrains-Air client) fills the
        // real insertion + auto-imports into additionalTextEdits here.
        if (item != null && client != null && item.additionalTextEdits.isNullOrEmpty()) {
            client.requestCompletionResolve(item) { resolved ->
                mailbox.post {
                    if (!docs.containsKey(doc.path)) return@post
                    val finalItem = resolved ?: item
                    if (!finalItem.additionalTextEdits.isNullOrEmpty()) {
                        val finalInsert = editTextFor(finalItem).ifEmpty { insert }
                        OutputLog.append(
                            "LSP",
                            LogLevel.LSP,
                            "completion resolve: '${finalItem.label}' -> " +
                                "${finalItem.additionalTextEdits!!.size} edit(s), insert='$finalInsert'",
                        )
                        applyCompletionEdits(doc, finalInsert, finalItem)
                    } else if (finalItem.command != null) {
                        applyViaCommand(doc, finalItem, insert)
                    } else {
                        applyCompletionEdits(doc, insert, finalItem)
                    }
                }
            }
            return
        }
        applyViaCommand(doc, item, insert)
    }

    /**
     * JetBrains-style apply: textEdit.newText is EMPTY and the real edit —
     * insertion text plus auto-imports — comes from executing the item's
     * command (server returns a WorkspaceEdit or pushes workspace/applyEdit).
     */
    private fun applyViaCommand(
        doc: Document,
        item: cn.enaium.lsp.model.CompletionItem?,
        insert: String,
    ) {
        val client = lsp.matchingClient(doc)
        val cmd = item?.command
        if (cmd != null && client != null && cmd.command.contains("completion.apply")) {
            OutputLog.append("LSP", LogLevel.LSP, "completion apply via command: ${cmd.command}")
            client.requestExecuteCommand(cmd.command, cmd.arguments) { result ->
                val edit = result?.let { parseCompletionWorkspaceEdit(doc.uri, it) }
                mailbox.post {
                    when {
                        edit != null -> {
                            OutputLog.append(
                                "LSP",
                                LogLevel.LSP,
                                "completion apply: workspace edit with " +
                                    "${(edit.changes?.values?.sumOf { it.size } ?: 0) + (edit.documentChanges?.size ?: 0)} change(s)",
                            )
                            onApplyWorkspaceEdit?.invoke(edit)
                        }
                        result != null -> {
                            OutputLog.append(
                                "LSP",
                                LogLevel.LSP,
                                "completion apply: unrecognized result: ${result.toString().take(300)}",
                            )
                        }
                        else -> {
                            // The server may apply the edit itself through a
                            // workspace/applyEdit request (JetBrains does):
                            // give it a moment and only insert the label if
                            // the document really did not change.
                            val before = doc.editor.getText()
                            scope.launch {
                                delay(400)
                                mailbox.post {
                                    if (docs.containsKey(doc.path)) {
                                        if (doc.editor.getText() == before) {
                                            OutputLog.append(
                                                "LSP",
                                                LogLevel.LSP,
                                                "completion apply: no edit arrived, inserting '${insert}'",
                                            )
                                            applyCompletionEdits(doc, insert, item)
                                        } else {
                                            OutputLog.append(
                                                "LSP",
                                                LogLevel.LSP,
                                                "completion apply: edit arrived via workspace/applyEdit",
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return
        }
        applyCompletionEdits(doc, insert, item)
    }

    /**
     * Replaces the identifier prefix with [insert] and applies the item's
     * [cn.enaium.lsp.model.CompletionItem.additionalTextEdits] (auto-imports)
     * — all in the item's original document coordinates, applied bottom-up
     * so earlier edits never shift later ones.
     */
    private fun applyCompletionEdits(
        doc: Document,
        insert: String,
        item: cn.enaium.lsp.model.CompletionItem?,
    ) {
        val pos = doc.editor.cursor
        val lineText = doc.editor.buffer.line(pos.line)
        var start = pos.index
        while (start > 0) {
            val c = lineText[start - 1]
            if (c.isLetterOrDigit() || c == '_') start-- else break
        }
        data class Edit(val from: DocPos, val to: DocPos, val text: String, val main: Boolean)

        val main = Edit(DocPos(pos.line, start), pos, insert, main = true)
        val edits = ArrayList<Edit>()
        edits.add(main)
        for (e in item?.additionalTextEdits ?: emptyList()) {
            edits.add(
                Edit(
                    DocPos(e.range.start.line, e.range.start.character),
                    DocPos(e.range.end.line, e.range.end.character),
                    e.newText,
                    main = false,
                ),
            )
        }
        // Bottom-up: later positions first (stable, so the main edit is
        // applied before an additional edit starting at the same spot).
        edits.sortWith(compareByDescending<Edit> { it.from.line }.thenByDescending { it.from.index })

        // Caret: end of the main edit, shifted by edits that come after it
        // in application order (they sit earlier in the document, e.g. an
        // import inserted at the top).
        var caret: DocPos? = null
        for (e in edits) {
            if (e.main) {
                val lines = e.text.count { it == '\n' }
                caret = if (lines > 0) {
                    DocPos(e.from.line + lines, e.text.substringAfterLast('\n').length)
                } else {
                    DocPos(e.from.line, e.from.index + e.text.length)
                }
            } else if (caret != null) {
                caret = DocPos(
                    caret.line + e.text.count { it == '\n' } - (e.to.line - e.from.line),
                    caret.index,
                )
            }
        }

        doc.suppressAutoCompletion = true
        try {
            // One undo step: the inserted completion and its auto-import
            // edits revert together.
            doc.editor.applyEdits(edits.map { EditorEdit(it.from, it.to, it.text) })
        } finally {
            doc.suppressAutoCompletion = false
        }
        if (caret != null) doc.editor.setCursor(caret)
    }

    /** Host hook: apply a WorkspaceEdit produced by a completion command. */
    var onApplyWorkspaceEdit: ((cn.enaium.lsp.model.WorkspaceEdit) -> Unit)? = null

    /**
     * Parses the result of a completion command into a [WorkspaceEdit]:
     * accepts the standard shapes (changes / documentChanges) and a bare
     * TextEdit array (treated as edits for [uri]).
     */
    private fun parseCompletionWorkspaceEdit(uri: String, element: kotlinx.serialization.json.JsonElement): cn.enaium.lsp.model.WorkspaceEdit? {
        when (element) {
            is kotlinx.serialization.json.JsonObject -> {
                if (element.containsKey("changes") || element.containsKey("documentChanges")) {
                    return runCatching {
                        kotlinx.serialization.json.Json.decodeFromJsonElement(
                            cn.enaium.lsp.model.WorkspaceEdit.serializer(),
                            element,
                        )
                    }.getOrNull()
                }
                return null
            }
            is kotlinx.serialization.json.JsonArray -> {
                val edits = runCatching {
                    kotlinx.serialization.json.Json.decodeFromJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(cn.enaium.lsp.model.TextEdit.serializer()),
                        element,
                    )
                }.getOrNull() ?: return null
                return cn.enaium.lsp.model.WorkspaceEdit(changes = mapOf(uri to edits))
            }
            else -> return null
        }
    }

    private fun closeCompletion(doc: Document) {
        doc.completionActive = false
        doc.completionItems = emptyList()
    }

    fun shutdownAll() {
        docs.values.forEach { it.close() }
        docs.clear()
    }
}


