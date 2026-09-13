package cn.enaium.imcode.app

import cn.enaium.imcode.config.Config
import cn.enaium.imcode.config.ConfigStore
import cn.enaium.imcode.config.RecentFile
import cn.enaium.imcode.config.WorkspaceEntry
import cn.enaium.imcode.editor.Document
import cn.enaium.imcode.editor.DocumentManager
import cn.enaium.imcode.lsp.LspManager
import cn.enaium.imcode.platform.Platform
import cn.enaium.imcode.platform.fileReadable
import cn.enaium.imcode.platform.ioFile
import cn.enaium.imcode.platform.isUnder
import cn.enaium.imcode.search.SearchHit
import cn.enaium.imcode.search.SearchService
import cn.enaium.imcode.ui.FileDialogs
import cn.enaium.imcode.ui.WorkspaceWindow
import cn.enaium.lsp.model.Location
import cn.enaium.lsp.model.TextEdit
import cn.enaium.lsp.model.WorkspaceEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Windowless application core: configuration, open documents, LSP lifecycle,
 * search and shared window state. Rendered by one or more [WindowCtx]
 * frames (hub + one SDL window per workspace); everything here runs on the
 * render thread of the owning window (mutations arrive via [Mailbox]).
 */
class AppCore(val mailbox: Mailbox) {
    var config: Config = ConfigStore.load().let { cfg ->
        if (cfg.lspServers.isEmpty()) {
            cfg.copy(lspServers = listOf(cn.enaium.imcode.config.LspServer(command = listOf("kotlin-lsp", "--stdio"))))
        } else cfg
    }

    val lsp = LspManager({ config }, mailbox, { config.rpcLogging })
    val documents = DocumentManager(
        config = { config },
        mailbox = mailbox,
        lsp = lsp,
        onDocumentOpened = { doc -> onDocumentOpened(doc) },
        onDocumentClosed = { _ -> },
        onNavigate = { path, line, index -> openLocation(path, line, index) },
    )
    val searchService = SearchService()
    val fileDialogs = FileDialogs()

    /**
     * Shared markdown renderer config (hover docs with fenced code blocks).
     * One instance for the process; destroyed in [shutdown].
     */
    private val markdownConfigLazy = lazy {
        cn.enaium.imgui.extensions.markdown.Markdown.create().also {
            cn.enaium.imgui.extensions.markdown.Markdown.setFormatFlags(
                it,
                cn.enaium.imgui.extensions.markdown.MdFormatFlags.COMMON_MARK_ALL,
            )
        }
    }
    val markdownConfig: cn.enaium.imgui.extensions.markdown.MarkdownConfigHandle
        get() = markdownConfigLazy.value

    /** Logical workspace list; each one is rendered in its own SDL window. */
    val workspaces = ArrayList<WorkspaceWindow>()
    var focusedWorkspace: WorkspaceWindow? = null

    var showSettings = false
    var showSearch = false
    var showSymbolSearch = false
    var showWelcome = true
    var showAbout = false

    val symbolSearchWindow = cn.enaium.imcode.ui.SymbolSearchWindow()

    /** True when a font-size change must be re-applied to every window. */
    var fontDirty = false

    // exit handling
    var exitRequested = false
    var canExitNow = false

    /** Confirm modal state; rendered by the hub window. */
    var confirmText: String? = null
    var confirmAction: (() -> Unit)? = null
    var confirmOpenOnce = false

    /** Workspace close-confirm modal; rendered by the workspace's own window. */
    var pendingCloseWs: WorkspaceWindow? = null
    var pendingCloseOpenOnce = false

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Set by the window host: called when [ws] needs a window (adopt or create). */
    var onOpenWorkspaceWindow: ((WorkspaceWindow) -> Unit)? = null

    /** Set by the window host: releases/destroys the window hosting [ws]. */
    var onFinalizeWorkspaceClose: ((WorkspaceWindow) -> Unit)? = null

    init {
        lsp.onDiagnostics = { doc, diags -> documents.applyDiagnostics(doc, diags) }
        lsp.onApplyEdit = { _, edit -> applyWorkspaceEdit(edit) }
        // When a server finishes its handshake, re-request semantic tokens
        // for the documents it owns: they were opened (and their token
        // request dropped) while the client was still starting.
        lsp.onServerReady = { documents.refreshLspHighlighting() }
        // Completion commands (JetBrains-style) return a WorkspaceEdit.
        documents.onApplyWorkspaceEdit = { edit -> applyWorkspaceEdit(edit) }
    }

    // ==================== config ====================

    fun updateConfig(cfg: Config) {
        config = cfg
    }

    fun addRecent(path: String) {
        val list = config.recentFiles.filter { it.path != path }
        config = config.copy(recentFiles = (listOf(RecentFile(path, Platform.currentTimeMillis())) + list).take(60))
    }

    fun removeRecent(path: String) {
        config = config.copy(recentFiles = config.recentFiles.filter { it.path != path })
    }

    private fun WorkspaceWindow.toEntry(): WorkspaceEntry = WorkspaceEntry(
        dir = dir,
        openFiles = openTabs.toList(),
        activeFile = activeFile,
        explorerWidth = explorerWidth,
        outputHeight = outputHeight,
    )

    /**
     * Persists the session. MERGE semantics: entries for workspaces that are
     * open right now are updated, entries that were persisted before but not
     * opened this session stay untouched, and newly opened workspaces are
     * appended. This keeps the persisted project list (the welcome page's
     * project record) intact even when "restore session on startup" is off
     * and no workspace was opened — a naive overwrite would wipe the list.
     */
    fun saveState() {
        val openByDir = workspaces.associateBy { it.dir }
        val merged = config.workspaces.map { old ->
            openByDir[old.dir]?.toEntry() ?: old
        } + workspaces
            .filter { ws -> config.workspaces.none { it.dir == ws.dir } }
            .map { it.toEntry() }
        // never persist the CLI-forced --lsp-log value
        val persisted = config.copy(workspaces = merged, rpcLogging = origRpcLogging ?: config.rpcLogging)
        ConfigStore.save(persisted)
    }

    // ==================== workspace management ====================

    /**
     * Persisted workspaces -> one SDL window each (called after the host set
     * [onOpenWorkspaceWindow]). When "restore session on startup" is off, the
     * previous session is NOT reopened — the project list page (welcome) is
     * shown instead and the user picks a project from there.
     */
    fun restoreWorkspaces() {
        if (!config.restoreWorkspacesOnStart) {
            showWelcome = true
            return
        }
        for (entry in config.workspaces) {
            // Skip entries whose directory no longer exists or is blank:
            // restoring them would open a blank hub window that swallows
            // the primary window's workspace slot.
            val dir = entry.dir
            if (dir.isBlank() || !ioFile(dir).isDirectory) continue
            openWorkspaceWindow(entry)
        }
        if (workspaces.isEmpty()) showWelcome = true
    }

    fun openWorkspaceWindow(entry: WorkspaceEntry? = null, dir: String? = null): WorkspaceWindow {
        val ws = WorkspaceWindow(entry?.dir ?: dir ?: return WorkspaceWindow(""))
        if (entry == null && dir == null) return ws
        ws.explorerWidth = entry?.explorerWidth ?: 260f
        // Legacy configs persisted the old default (180); treat anything
        // below the new default as "never customized" so existing users get
        // the taller log pane too.
        val persisted = entry?.outputHeight
        ws.outputHeight = if (persisted != null && persisted >= 280f) persisted else 280f
        if (entry != null) {
            entry.openFiles.forEach { ws.activateTab(it) }
            ws.activeFile = entry.activeFile
        }
        workspaces.add(ws)
        showWelcome = false
        onOpenWorkspaceWindow?.invoke(ws)

        for (path in (entry?.openFiles ?: emptyList())) {
            if (!documents.isOpen(path)) documents.open(path, ws.dir)
        }
        saveState()
        return ws
    }

    fun removeWorkspace(ws: WorkspaceWindow) {
        workspaces.remove(ws)
        if (focusedWorkspace == ws) focusedWorkspace = null
        pendingCloseWs = null
        // an explicitly closed workspace leaves the persisted project list too
        config = config.copy(workspaces = config.workspaces.filter { it.dir != ws.dir })
        if (workspaces.isEmpty()) showWelcome = true
        saveState()
    }

    fun focusWorkspace(ws: WorkspaceWindow) {
        focusedWorkspace = ws
        showWelcome = false
    }

    fun requestWorkspaceClose(ws: WorkspaceWindow) {
        if (pendingCloseWs == ws) return
        initiateWorkspaceClose(ws)
    }

    fun initiateWorkspaceClose(ws: WorkspaceWindow) {
        val dirty = ws.openTabs.count { documents.get(it)?.dirty == true }
        if (dirty > 0) {
            pendingCloseWs = ws
            pendingCloseOpenOnce = true
        } else {
            closeWorkspaceDocuments(ws)
            finalizeWorkspaceClose(ws)
        }
    }

    fun confirmWorkspaceClose(ws: WorkspaceWindow, save: Boolean) {
        if (save) {
            for (path in ws.openTabs.toList()) {
                val doc = documents.get(path) ?: continue
                if (doc.dirty) documents.save(doc)
            }
        }
        closeWorkspaceDocuments(ws)
        finalizeWorkspaceClose(ws)
    }

    fun cancelWorkspaceClose() {
        pendingCloseWs = null
    }

    private fun finalizeWorkspaceClose(ws: WorkspaceWindow) {
        pendingCloseWs = null
        onFinalizeWorkspaceClose?.invoke(ws)
    }

    private fun closeWorkspaceDocuments(ws: WorkspaceWindow) {
        for (path in ws.openTabs.toList()) {
            val doc = documents.get(path) ?: continue
            ws.removeTab(path)
            documents.close(doc)
        }
    }

    fun activeWorkspace(): WorkspaceWindow? = focusedWorkspace ?: workspaces.lastOrNull()

    fun activeDoc(): Document? = activeWorkspace()?.let { ws -> ws.activeFile?.let { documents.get(it) } }

    /** Scope for the next "Find in Files" opening (set by the triggering window). */
    var searchScopeDir: String? = null

    // --type-test diagnostics: injects characters into the active editor
    var typeTestRemaining = 0
    var typeTestCounter = 0
    var typeTestChar: UInt = 'p'.code.toUInt()
    var frameCount = 0

    // ==================== dialogs (rendered by the hub window) ====================

    fun requestOpenFile(ws: WorkspaceWindow?) {
        dialogTargetWs = ws
        fileDialogs.pickFiles("Open File", "All files (*.*){.},Kotlin (*.kt;*.kts){.kt,.kts},Text (*.txt){.txt}") { paths ->
            for (path in paths) {
                val target = dialogTargetWs ?: workspaceFor(path) ?: ensureWorkspace(path)
                openFileInWorkspace(target, path)
            }
            dialogTargetWs = null
        }
    }

    fun requestOpenFolder() {
        fileDialogs.pickDirectory("Open Workspace Folder") { dirs ->
            val dir = dirs.firstOrNull() ?: return@pickDirectory
            val dirFile = ioFile(dir)
            if (!dirFile.isDirectory) return@pickDirectory
            workspaces.firstOrNull { it.dir == dirFile.absolutePath }?.let { focusWorkspace(it); return@pickDirectory }
            openWorkspaceWindow(dir = dirFile.absolutePath)
        }
    }

    private var dialogTargetWs: WorkspaceWindow? = null

    // ==================== font ====================

    fun adjustFont(delta: Float) = adjustFontTo((config.fontSize + delta).coerceIn(10f, 24f))

    fun adjustFontTo(size: Float) {
        config = config.copy(fontSize = size)
        fontDirty = true
    }

    fun workspaceFor(path: String): WorkspaceWindow? =
        workspaces.firstOrNull { ws -> isUnder(ws.dir, path) }

    private fun findProjectRoot(path: String): String? {
        val markers = listOf(
            "settings.gradle", "settings.gradle.kts", "settings.gradle.groovy",
            "build.gradle", "build.gradle.kts", "pom.xml", "package.json",
            ".git", "Cargo.toml", "go.mod", "pyproject.toml", "composer.json",
        )
        var dir = ioFile(path).parent ?: return null
        while (true) {
            val entries = dir.listFiles() ?: return null
            if (entries.any { it.name in markers }) return dir.absolutePath
            val parent = dir.parent ?: return null
            if (parent.absolutePath == dir.absolutePath) return null
            dir = parent
        }
    }

    private fun workspaceDirFor(path: String): String =
        findProjectRoot(path) ?: ioFile(path).parent?.absolutePath ?: Platform.userHome

    fun ensureWorkspace(path: String): WorkspaceWindow {
        workspaceFor(path)?.let { return it }
        return openWorkspaceWindow(dir = workspaceDirFor(path))
    }

    // ==================== file operations ====================

    fun openFileInWorkspace(ws: WorkspaceWindow, path: String, jumpTo: Pair<Int, Int>? = null) {
        val existing = documents.get(path)
        if (existing != null) {
            workspaces.forEach { it.removeTab(path) }
            ws.activateTab(path)
            if (jumpTo != null) documents.navigate(existing, jumpTo.first, jumpTo.second)
            return
        }
        // A tab for an unreadable target (missing file or archive entry,
        // e.g. a go-to-definition target inside a source jar) would render
        // as an empty editor with no document — check before creating it.
        if (!fileReadable(path)) {
            OutputLog.error("Navigator", "cannot open $path (missing or unreadable)")
            return
        }
        ws.activateTab(path)
        documents.open(path, ws.dir, jumpTo)
    }

    private fun onDocumentOpened(doc: Document) {
        addRecent(doc.path)
        doc.workspaceDir?.let { dir ->
            workspaces.firstOrNull { it.dir == dir }?.registerTab(doc.path)
        }
    }

    fun openRecent(path: String) {
        val f = ioFile(path)
        if (!f.isFile) {
            OutputLog.warn("App", "recent file no longer exists: $path")
            removeRecent(path)
            return
        }
        openFileInWorkspace(ensureWorkspace(path), path)
    }

    fun openSearchHit(hit: SearchHit) {
        if (!ioFile(hit.path).isFile) return
        val jump = if (hit.line > 0) hit.line to 0 else null
        openFileInWorkspace(ensureWorkspace(hit.path), hit.path, jump)
    }

    fun openLocation(path: String, line: Int, index: Int) {
        // Jump targets (go-to-definition, structure/symbol search) can live
        // outside every workspace root — dependency sources, generated code.
        // Open them as a tab in the active workspace instead of spawning a
        // new project window; fall back to a new window only when the file
        // belongs to no workspace and none is open.
        val ws = workspaceFor(path) ?: activeWorkspace() ?: openWorkspaceWindow(dir = workspaceDirFor(path))
        openFileInWorkspace(ws, path, line to index)
    }

    /** Dev/CLI helper: open [path] in a workspace right after startup. */
    fun openPathAtStartup(path: String) {
        openFileInWorkspace(ensureWorkspace(path), path)
    }

    // ==================== LSP helpers ====================

    fun reloadLspServers() {
        lsp.syncLifecycle(documents.openDocs)
    }

    /** Dev/CLI helper: force RPC logging regardless of the persisted config. */
    private var origRpcLogging: Boolean? = null
    fun forceRpcLogging(enabled: Boolean) {
        if (origRpcLogging == null) origRpcLogging = config.rpcLogging
        config = config.copy(rpcLogging = enabled)
    }

    /** Restores the persisted RPC logging setting (CLI runs must not leak it). */
    fun restoreRpcLogging() {
        origRpcLogging?.let { config = config.copy(rpcLogging = it) }
        origRpcLogging = null
    }

    fun activeServerStatus(): String = lsp.statusLine()

    // ==================== exit ====================

    fun requestExit() {
        if (exitRequested) return
        exitRequested = true
        val dirty = documents.openDocs.count { it.dirty }
        if (dirty > 0) {
            confirmText = "Exit ImCode? $dirty file(s) have unsaved changes."
            confirmAction = {
                documents.saveAll()
                doExit()
            }
            confirmOpenOnce = true
        } else {
            doExit()
        }
    }

    fun doExit() {
        canExitNow = true
    }

    fun shutdown() {
        if (markdownConfigLazy.isInitialized()) {
            try {
                cn.enaium.imgui.extensions.markdown.Markdown.destroy(markdownConfigLazy.value)
            } catch (_: Throwable) {
            }
        }
        lsp.shutdownAll()
        documents.shutdownAll()
        searchService.shutdown()
        ioScope.cancel()
        saveState()
    }

    // ==================== workspace edit application ====================

    /** Applies an LSP [WorkspaceEdit] (rename / applyEdit) to open documents. */
    fun applyWorkspaceEditPublic(edit: WorkspaceEdit) = applyWorkspaceEdit(edit)

    private fun applyWorkspaceEdit(edit: Any) {
        if (edit !is WorkspaceEdit) return
        edit.changes?.forEach { (uri, textEdits) -> applyEditsToDoc(uri, textEdits) }
        edit.documentChanges?.forEach { change ->
            if (change is cn.enaium.lsp.model.DocumentChange.Edit) {
                val td = change.value
                val textEdits = td.edits.mapNotNull { e ->
                    when (e) {
                        is cn.enaium.lsp.model.TextEditOrSnippet.Edit -> e.value
                        is cn.enaium.lsp.model.TextEditOrSnippet.Snippet ->
                            TextEdit(e.value.range, e.value.snippet.value)
                    }
                }
                applyEditsToDoc(td.textDocument.uri, textEdits)
            }
        }
    }

    private fun applyEditsToDoc(uri: String, textEdits: List<TextEdit>) {
        val path = cn.enaium.imcode.util.Uri.uriToPath(uri) ?: return
        val doc = documents.get(path) ?: return
        val newText = applyTextEdits(doc.text(), textEdits)
        if (newText != null && newText != doc.text()) {
            doc.editor.setText(newText)
            doc.dirty = true
            doc.version++
        }
    }

    private fun applyTextEdits(text: String, edits: List<TextEdit>): String? {
        if (edits.isEmpty()) return null
        val lineStarts = ArrayList<Int>()
        lineStarts.add(0)
        for (i in text.indices) if (text[i] == '\n') lineStarts.add(i + 1)

        fun offset(pos: cn.enaium.lsp.model.Position): Int {
            val line = pos.line.coerceIn(0, lineStarts.size - 1)
            val base = lineStarts[line]
            return (base + pos.character).coerceIn(base, text.length)
        }

        val ordered = edits.sortedByDescending { offset(it.range.start) }
        var result = text
        for (edit in ordered) {
            val start = offset(edit.range.start)
            val end = offset(edit.range.end)
            if (start < 0 || end < start || end > result.length) continue
            result = result.substring(0, start) + edit.newText + result.substring(end)
        }
        return if (result == text) null else result
    }
}