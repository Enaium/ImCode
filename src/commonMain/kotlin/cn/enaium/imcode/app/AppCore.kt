package cn.enaium.imcode.app

import cn.enaium.imcode.config.Config
import cn.enaium.imcode.config.ConfigStore
import cn.enaium.imcode.config.OpenFolderPolicy
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
    init {
        // Every logged error becomes a balloon: the call sites (LSP start
        // failures, file read/save failures) are exactly what a user must not
        // miss, and routing it here keeps them from having to remember.
        OutputLog.onError = { tag, text -> cn.enaium.imcode.ui.Notifications.error(tag, text) }
    }

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

    /** Debug sessions: adapters, breakpoints and the toolbar's actions. */
    val debug = DebugController(this)

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

    /**
     * Window currently drawing, set by the host every frame. Actions taken
     * from that window's UI (menu, panes, shortcuts) run while it is set, so
     * anything they open knows which window asked for it.
     */
    var currentWindowId: Int = -1

    /** Window that opened the floating UI; -1 when nothing is open. */
    var uiWindowId: Int = -1

    /**
     * True when [windowId] is the window the floating UI belongs to.
     *
     * Settings, search, the file dialogs and the confirm modal are process
     * state, not per-window state: without an owner they would be drawn by
     * every window (and, before this, by the primary window only, which put a
     * dialog opened in one project's window on top of the other project).
     */
    fun ownsFloatingUi(windowId: Int): Boolean = uiWindowId == -1 || uiWindowId == windowId

    /**
     * True while one of the IDE's own text fields owns the keyboard (settings,
     * search, symbol search, Go to Line, the new-file prompt).
     *
     * The editor reads typed characters from the SDL text-input events rather
     * than from ImGui, so without this a dialog and the code behind it both
     * receive what the user types into the dialog.
     */
    val hostFieldFocused: Boolean
        get() = showSearch || showSettings || showSymbolSearch || showGotoLine

    var showSettings = false
        set(value) {
            if (value) uiWindowId = currentWindowId
            field = value
        }
    var showSearch = false
        set(value) {
            if (value) uiWindowId = currentWindowId
            field = value
        }
    var showSymbolSearch = false
        set(value) {
            if (value) uiWindowId = currentWindowId
            field = value
        }
    var showWelcome = true
    var showAbout = false
        set(value) {
            if (value) uiWindowId = currentWindowId
            field = value
        }
    var showNotifications = false
        set(value) {
            if (value) uiWindowId = currentWindowId
            field = value
        }
    var showGotoLine = false
        set(value) {
            if (value) uiWindowId = currentWindowId
            field = value
        }
    var showProgress = false
        set(value) {
            if (value) uiWindowId = currentWindowId
            field = value
        }

    val symbolSearchWindow = cn.enaium.imcode.ui.SymbolSearchWindow()

    /** True when a font-size change must be re-applied to every window. */
    var fontDirty = false

    // exit handling
    var exitRequested = false
    var canExitNow = false

    /** Confirm modal state; rendered by the window that asked for it. */
    var confirmText: String? = null
        set(value) {
            if (value != null) uiWindowId = currentWindowId
            field = value
        }
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
        // Reopen the project the user was working on last, not every project
        // in the persisted list: that list doubles as the welcome page's
        // project record, so restoring all of it opened one window per entry
        // (stale projects included). The most recent folder is the head of
        // recentFolders; a config that has none falls back to the last entry.
        val last = config.recentFolders.firstOrNull()?.path
        val entry = last?.let { dir -> config.workspaces.lastOrNull { it.dir == dir } }
            ?: config.workspaces.lastOrNull()
        // Skip an entry whose directory no longer exists: restoring it would
        // open a blank window that swallows the primary window's slot.
        if (entry != null && entry.dir.isNotBlank() && ioFile(entry.dir).isDirectory) {
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
        // A fresh open (not a restored session) is a folder the user chose.
        if (entry == null) {
            addRecentFolder(ws.dir)
            saveState()
        }
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
        set(value) {
            if (value != null) uiWindowId = currentWindowId
            field = value
        }

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
        uiWindowId = currentWindowId
        fileDialogs.pickDirectory("Open Workspace Folder") { dirs ->
            val dir = dirs.firstOrNull() ?: return@pickDirectory
            val dirFile = ioFile(dir)
            if (!dirFile.isDirectory) {
                OutputLog.warn("App", "open folder: $dir is not a directory; ignored")
                return@pickDirectory
            }
            openFolder(dirFile.absolutePath)
        }
    }

    /**
     * Opens [dir] the way [Config.openFolderPolicy] says: focus it when it is
     * already open, reuse the current window, open a new one, or ask first.
     * Every folder opened this way is remembered in [Config.recentFolders].
     */
    fun openFolder(dir: String) {
        workspaces.firstOrNull { it.dir == dir }?.let {
            focusWorkspace(it)
            return
        }
        addRecentFolder(dir)
        saveState()
        OutputLog.info("App", "open folder: $dir (policy=${config.openFolderPolicy})")
        when (config.openFolderPolicy) {
            OpenFolderPolicy.CurrentWindow -> adoptInCurrentWindow(dir)
            OpenFolderPolicy.NewWindow -> openWorkspaceWindow(dir = dir)
            else -> {
                pendingFolder = dir
                pendingFolderOpenOnce = true
            }
        }
    }

    /** Folder waiting for the user to pick a window (the "ask" policy). */
    var pendingFolder: String? = null

    /** True while the choice modal still needs opening. */
    var pendingFolderOpenOnce = false

    /** Host hook: put [ws] into the focused window; false when there is none. */
    var onAdoptWorkspaceInCurrentWindow: ((WorkspaceWindow) -> Boolean)? = null

    /**
     * Replaces the focused window's workspace with [dir]: the old one's
     * documents close, the window keeps its place (its size and position).
     */
    fun adoptInCurrentWindow(dir: String) {
        val ws = WorkspaceWindow(dir)
        val adopted = onAdoptWorkspaceInCurrentWindow?.invoke(ws) == true
        if (!adopted) {
            OutputLog.info("App", "no window to adopt into; opening $dir in a new one")
            openWorkspaceWindow(dir = dir)
            return
        }
        OutputLog.info("App", "adopted $dir into the focused window")
        val previous = focusedWorkspace
        if (previous != null && previous !== ws) {
            workspaces.remove(previous)
            // The old workspace's editors belong to it, not to the window.
            for (doc in documents.openDocs.toList()) {
                if (doc.workspaceDir == previous.dir) documents.close(doc)
            }
        }
        workspaces.add(ws)
        focusWorkspace(ws)
        showWelcome = false
    }

    /** Records a folder in the recent list (newest first). */
    fun addRecentFolder(dir: String) {
        val list = config.recentFolders.filter { it.path != dir }
        config = config.copy(
            recentFolders = (listOf(RecentFile(dir, Platform.currentTimeMillis())) + list).take(20),
        )
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
        // Apply through the editor's batch API: setText() bypassed
        // onTextChange entirely, so the server was never told about the edit
        // (stale diagnostics) and the highlight caches kept spans for the old
        // text (mismatched ranges). It also cleared the undo stack.
        // Bottom-up so earlier edits do not shift later ranges.
        val ordered = textEdits.sortedWith(
            compareByDescending<TextEdit> { it.range.start.line }
                .thenByDescending { it.range.start.character },
        )
        val edits = ordered.map {
            cn.enaium.lsp.edit.EditorEdit(
                cn.enaium.lsp.edit.DocPos(it.range.start.line, it.range.start.character),
                cn.enaium.lsp.edit.DocPos(it.range.end.line, it.range.end.character),
                it.newText,
            )
        }
        // onTextChange (fired by applyEdits) updates dirty/version and sends
        // the didChange notification.
        doc.editor.applyEdits(edits)
    }

}