package cn.enaium.imcode.editor

import cn.enaium.imcode.platform.ioFile
import cn.enaium.lsp.edit.Editor
import cn.enaium.lsp.model.Diagnostic

/** A diagnostics entry attached to one document line. */
data class DiagInfo(val severity: Int, val message: String)

/**
 * One open file: an [Editor] (lsp-edit widget) plus bookkeeping
 * (version counter, dirty flag, LSP diagnostics, hover cache).
 * All members are only touched on the render thread.
 */
class Document(
    val path: String,
    val workspaceDir: String?,
    val uri: String,
    languageId: String,
    val editor: Editor,
) {
    var languageId: String = languageId
        private set

    /** True when the file is not valid text (binary) and renders as hex. */
    var isBinary: Boolean = false

    /** Raw bytes for the hex view (binary files only). */
    var binaryBytes: ByteArray = ByteArray(0)

    /** Tree-sitter highlighter when this file uses local highlighting. */
    var treeSitter: cn.enaium.lsp.edit.syntax.treesitter.TreeSitterHighlighter? = null

    /** LSP semantic-token spans per line (overlays the tree-sitter base). */
    var semanticSpans: Map<Int, List<cn.enaium.lsp.edit.TokenSpan>>? = null

    /** Hex editor instance (binary files only). */
    var hexEditor: cn.enaium.imgui.extensions.memoryeditor.MemoryEditorInstance? = null

    /** Bumped on every user edit (UI-level counter). */
    var version: Int = 1

    /** Text of the last save (or the opened content); dirty is text != savedText. */
    var savedText: String = ""

    var dirty: Boolean = false

    /** Raw diagnostics from the last publishDiagnostics (main-thread access). */
    var diagnostics: List<Diagnostic> = emptyList()

    /** Inlay hints from the last textDocument/inlayHint, grouped by line. */
    var inlayHints: Map<Int, List<cn.enaium.lsp.model.InlayHint>> = emptyMap()

    /** Diagnostics grouped per line (0-based, matching the editor). */
    var diagByLine: Map<Int, List<DiagInfo>> = emptyMap()
    var errorCount: Int = 0
    var warningCount: Int = 0

    // ---- hover cache (main thread) ----
    /** "line:index" of the hovered word that [hoverText] belongs to. */
    var hoverPosKey: String? = null
    var hoverText: String? = null

    /**
     * Whether the editor currently reports the pointer resting on a hover
     * target. The popup itself may keep the hover alive after this goes
     * false (pointer moved onto the popup).
     */
    var hoverActive: Boolean = false
    var hoverLoading: Boolean = false

    /** resolved hover per "line:index" (null = known-empty); cleared on edits. */
    var hoverCache: MutableMap<String, String?> = HashMap()

    // ---- completion popup state (render thread) ----
    var completionInFlight: Boolean = false

    /** While true, edits do not re-trigger the completion popup (accepting
     *  a completion must not reopen it). */
    var suppressAutoCompletion: Boolean = false
    var completionItems: List<CompletionRow> = emptyList()
    var completionSelected: Int = 0
    var completionActive: Boolean = false

    // ---- document symbol tree (structure panel) ----
    var symbols: List<cn.enaium.lsp.model.DocumentSymbol> = emptyList()

    /** code lenses grouped by line (0-based). */
    var codeLenses: Map<Int, List<cn.enaium.lsp.model.CodeLens>> = emptyMap()

    val name: String get() = ioFile(path).name

    fun text(): String = editor.getText()

    fun clearDiagnostics() {
        diagnostics = emptyList()
        diagByLine = emptyMap()
        errorCount = 0
        warningCount = 0
    }

    fun close() {
        hexEditor?.let {
            cn.enaium.imgui.extensions.memoryeditor.MemoryEditor.destroy(it)
        }
        hexEditor = null
        // The editor holds no native resources beyond the font; nothing to
        // release beyond dropping references.
    }
}

/** One row of the completion popup: what to insert vs what to display. */
data class CompletionRow(
    val label: String,
    val insert: String,
    /** labelDetails.detail — parameters/source, shown after the label. */
    val detail: String = "",
    val kind: Int? = null,
    val doc: String = "",
    /**
     * labelDetails.description — the type ("Double", "Unit"), drawn
     * right-aligned at the row end.
     */
    val trailing: String = "",
    /** True once completionItem/resolve filled this row's deferred fields. */
    val resolved: Boolean = false,
    /** True while a resolve request is in flight for this row. */
    val resolving: Boolean = false,
    /** The original LSP item (resolve / additionalTextEdits on accept). */
    val item: cn.enaium.lsp.model.CompletionItem? = null,
)
