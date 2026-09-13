package cn.enaium.imcode.ui

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiChildFlags
import cn.enaium.imgui.ImGuiCol
import cn.enaium.imgui.ImGuiDockNodeFlags
import cn.enaium.imgui.ImGuiDir
import cn.enaium.imgui.ImGuiTabItemFlags
import cn.enaium.imgui.ImGuiTreeNodeFlags
import cn.enaium.imgui.ImGuiWindowFlags
import cn.enaium.imgui.ImVec2
import cn.enaium.imgui.ImVec4
import cn.enaium.imcode.app.AppCore
import cn.enaium.imcode.app.LogLevel
import cn.enaium.imcode.app.OutputLog
import cn.enaium.imcode.editor.Document
import cn.enaium.imcode.editor.DocumentManager
import cn.enaium.imcode.platform.ioFile
import cn.enaium.lsp.edit.DocPos
import cn.enaium.lsp.model.DocumentSymbol
import cn.enaium.lsp.model.Position


/**
 * One workspace rendered FULLSCREEN inside its own SDL window:
 *
 *   [ main menu bar (shared)                  ]
 *   [ docking area: Explorer | Editors       ]
 *   [               Output                    ]
 *
 * The layout is a DockSpace (imgui docking branch): the Explorer, Editors and
 * Output panes are docked windows the user can move, re-dock and resize with
 * the standard docking gestures. The initial split honors the persisted
 * explorer width / output height.
 */
class WorkspaceWindow(val dir: String) {

    /** Tab order of open files inside this workspace. */
    val openTabs = LinkedHashSet<String>()
    var activeFile: String? = null

    // layout state (persisted back into the config)
    var explorerWidth = 260f
    var outputHeight = 280f

    // unsaved-tab modal
    private var pendingClosePath: String? = null
    private var openModalOnce = false

    // new-file modal
    private var newFileName: String? = null
    private val newFileOpen = BooleanArray(1) { false }

    /** Docking node ids of the panes (built once on the first dock frame). */
    private var dockExplorer = 0
    private var dockEditors = 0
    private var dockOutput = 0
    private var dockStructure = 0
    private var dockingReady = false

    /** Last active file; when it changes the tab bar scrolls to the tab. */
    private var lastActiveFile: String? = null

    /** Symbols of the active file's last documentSymbol response. */
    private var structureSymbols: List<DocumentSymbol> = emptyList()

    /** Path of the file the structure/lens state belongs to. */
    private var structureFile: String? = null

    /** True when a documentSymbol request is in flight for the active file. */
    private var structureRequested = false

    /** (path, version) of the last documentSymbol request/response. */
    private var structureKey: Pair<String, Int>? = null

    /** Empty-result retry counter for documentSymbol. */
    private var structureRetries = 0

    /** Empty-result retry counter for codeLens. */
    private var codeLensRetries = 0

    /** Last documentSymbol/codeLens/signature request timestamps (ms). */
    private var lastStructureRequestMs = 0L
    private var lastCodeLensRequestMs = 0L
    private var lastSignatureRequestMs = 0L

    /** (path, version, line, index) of the last signature request. */
    private var signatureKey: Quad<String, Int, Int, Int>? = null

    /** (path, version) of the last codeLens request/response. */
    private var codeLensKey: Pair<String, Int>? = null

    /** True when a codeLens request is in flight for the active file. */
    private var codeLensRequested = false

    /** Empty-result retry counter for foldingRange. */
    private var foldingRetries = 0

    /** Last foldingRange request timestamp (ms). */
    private var lastFoldingRequestMs = 0L

    /** (path, version) of the last foldingRange request/response. */
    private var foldingKey: Pair<String, Int>? = null

    /** True when a foldingRange request is in flight for the active file. */
    private var foldingRequested = false

    /** Empty-result retry counter for inlayHint. */
    private var inlayHintsRetries = 0

    /** Last inlayHint request timestamp (ms). */
    private var lastInlayHintsRequestMs = 0L

    /** (path, version) of the last inlayHint request/response. */
    private var inlayHintsKey: Pair<String, Int>? = null

    /** True when an inlayHint request is in flight for the active file. */
    private var inlayHintsRequested = false

    /** Symbol the structure tree should navigate to (path, line, index). */
    private var pendingStructureNav: Triple<String, Int, Int>? = null

    /** Signature help popup state (active file). */
    private var signatureHelp: cn.enaium.lsp.model.SignatureHelp? = null
    private var signatureRequested = false
    private var signatureActive = false

    /** Rename dialog state. */
    private var renameOpen = false

    /** Caret position when the rename editor opened (leaving it exits). */
    private var renamePopupCursor: DocPos? = null

    /** Focus the name field on the first frame the dialog renders. */
    private var renameFocusInput = false

    /** Code actions for the last request (rendered in the list window). */
    private var codeActions: List<cn.enaium.lsp.model.CodeAction> = emptyList()

    /** Whether the code-actions list window is showing. */
    private var codeActionsOpen = false

    /** Document the actions were requested for. */
    private var codeActionDoc: Document? = null
    private var renameTarget: Pair<Int, Int>? = null
    private var renameText: String? = null

    /** References panel state. */
    private var referencesOpen = false
    private var referencesList: List<cn.enaium.lsp.model.Location> = emptyList()

    /** Screen rect of the editor body from the last frame (popup positioning). */
    var editorBodyRectMin: ImVec2 = ImVec2(0f, 0f)
    var editorBodyRectMax: ImVec2 = ImVec2(0f, 0f)
    val isMouseOverEditorBody: Boolean
        get() {
            val m = cn.enaium.imgui.ImGui.getMousePos()
            return m.x >= editorBodyRectMin.x && m.x <= editorBodyRectMax.x &&
                    m.y >= editorBodyRectMin.y && m.y <= editorBodyRectMax.y
        }

    private val explorer: ExplorerWidget by lazy {
        ExplorerWidget(dir) { path -> coreRef.openFileInWorkspace(this, path) }
    }
    private val output = OutputPanel { coreRef.config }

    private lateinit var coreRef: AppCore

    fun title(): String = "ImCode - ${ioFile(dir).name}"

    // ==================== public actions (render thread) ====================

    /** Adds [path] to the tab list + activates it (user-originated opens). */
    fun activateTab(path: String) {
        openTabs.add(path)
        activeFile = path
    }

    /**
     * Registers a tab WITHOUT stealing activation. Used when an async file
     * read completes: must not yank the user away from the tab they just
     * clicked (was the cause of "switching reverts to the old tab").
     */
    fun registerTab(path: String) {
        openTabs.add(path)
    }

    fun removeTab(path: String) {
        openTabs.remove(path)
        if (activeFile == path) activeFile = openTabs.lastOrNull()
    }

    fun requestClose(path: String) {
        val doc = coreRef.documents.get(path) ?: return
        if (doc.dirty) {
            pendingClosePath = path
            openModalOnce = true
        } else {
            closeNow(path)
        }
    }

    private fun closeNow(path: String) {
        val doc = coreRef.documents.get(path) ?: return
        removeTab(path)
        coreRef.documents.close(doc)
    }

    // ==================== bulk tab close (context menu) ====================

    /** Closes every tab except [keep]. Dirty tabs stay open with a prompt. */
    fun closeTabsExcept(keep: String? = null) {
        for (path in openTabs.toList()) {
            if (path == keep) continue
            val doc = coreRef.documents.get(path) ?: continue
            if (doc.dirty) {
                requestClose(path) // unsaved: prompt (one modal at a time)
            } else {
                closeNow(path)
            }
        }
    }

    /** Closes tabs left of [anchor] (excluding it); dirty ones prompt. */
    fun closeTabsLeftOf(anchor: String) {
        for (path in openTabs.toList()) {
            if (path == anchor) break
            val doc = coreRef.documents.get(path) ?: continue
            if (doc.dirty) requestClose(path) else closeNow(path)
        }
    }

    /** Closes tabs right of [anchor] (excluding it); dirty ones prompt. */
    fun closeTabsRightOf(anchor: String) {
        var seen = false
        for (path in openTabs.toList()) {
            if (path == anchor) { seen = true; continue }
            if (!seen) continue
            val doc = coreRef.documents.get(path) ?: continue
            if (doc.dirty) requestClose(path) else closeNow(path)
        }
    }

    /** Closes tabs without unsaved changes (keeps dirty ones). */
    fun closeUnmodified() {
        for (path in openTabs.toList()) {
            val doc = coreRef.documents.get(path) ?: continue
            if (!doc.dirty) closeNow(path)
        }
    }

    fun promptNewFile() {
        newFileName = ""
        newFileOpen[0] = true
    }

    /** True when [this] workspace is currently active in its window. */
    private var isFocusedThisFrame = false
    private var paneFocused = false
    val isFocusedWindow: Boolean get() = isFocusedThisFrame

    // ==================== rendering ====================

    fun draw(core: AppCore) {
        coreRef = core
        paneFocused = false

        // Route the editor context-menu LSP actions to this window's UI.
        core.documents.onFindReferences = { _ -> openReferences(core) }
        core.documents.onRenameRequested = { _ -> openRename(core) }
        core.documents.onCodeActionsRequested = { _ -> openCodeActions(core) }

        drawMenuBar(core, this)

        // the fullscreen layout starts BELOW the menu bar (it would cover it otherwise)
        val menuH = ImGui.getFrameHeight()

        val w = ImGui.getIO().displaySize.x
        val h = ImGui.getIO().displaySize.y - menuH

        // ---- dock host window: owns the DockSpace below the menu bar ----
        // Official fullscreen-dockspace pattern: the DockSpace must be submitted
        // even when Begin() returns false, otherwise every window docked into it
        // loses its parent and floats as a separate window.
        ImGui.pushStyleVarFloat(cn.enaium.imgui.ImGuiStyleVar.WINDOW_ROUNDING, 0f)
        ImGui.pushStyleVarFloat(cn.enaium.imgui.ImGuiStyleVar.WINDOW_BORDER_SIZE, 0f)
        ImGui.pushStyleVarVec2(cn.enaium.imgui.ImGuiStyleVar.WINDOW_PADDING, ImVec2(0f, 0f))
        ImGui.setNextWindowPos(ImVec2(0f, menuH), cn.enaium.imgui.ImGuiCond.ALWAYS)
        ImGui.setNextWindowSize(ImVec2(w, h), cn.enaium.imgui.ImGuiCond.ALWAYS)
        ImGui.begin(
            "##ws-host-${dir}",
            null,
            ImGuiWindowFlags.NO_DOCKING or ImGuiWindowFlags.NO_TITLE_BAR or ImGuiWindowFlags.NO_COLLAPSE or
                    ImGuiWindowFlags.NO_RESIZE or ImGuiWindowFlags.NO_MOVE or ImGuiWindowFlags.NO_BRING_TO_FRONT_ON_FOCUS or
                    ImGuiWindowFlags.NO_NAV_FOCUS or ImGuiWindowFlags.NO_BACKGROUND,
        )
        // stable, window-scoped dock node id (hash of the host window id + label)
        val dockId = ImGui.getID("##ws-dock-${dir}")
        // DockSpace first: it creates AND sizes the root node (the split below
        // needs a non-zero base size)
        ImGui.dockSpace(dockId, ImVec2(0f, 0f), ImGuiDockNodeFlags.NO_CLOSE_BUTTON)
        if (!dockingReady) setupDocking(dockId, w, h)
        if (ImGui.isWindowFocused()) paneFocused = true
        ImGui.end()
        ImGui.popStyleVar(3)

        // ---- the three docked panes (any order; the dock system places them) ----
        renderExplorerPane()
        renderEditorsPane(core)
        renderStructurePane(core)
        renderOutputPane()

        // modals (drawn inside this window's context)
        pendingClosePath?.let { renderUnsavedModal(core, it) }
        if (core.pendingCloseWs == this) renderWorkspaceCloseModal(core)
        if (newFileOpen[0]) renderNewFileModal(core)
        if (renameOpen) renderRenameModal(core)
        if (codeActionsOpen) renderCodeActionsWindow(core)
        if (referencesOpen) renderReferencesWindow(core)

        isFocusedThisFrame = paneFocused
        if (isFocusedThisFrame) core.focusWorkspace(this)
    }

    /** Creates the initial dock layout (Explorer left, Editors + Output split
     *  vertically) honoring the persisted pane sizes; runs once per window,
     *  after the DockSpace sized the root node. */
    private fun setupDocking(dockId: Int, w: Float, h: Float) {
        dockingReady = true
        // the root node was created and sized by the DockSpace call this frame
        val explorerRatio = (explorerWidth / w).coerceIn(0.12f, 0.75f)
        val outputRatio = (outputHeight / h).coerceIn(0.08f, 0.6f)
        // for Left/Down splits the passed ratio IS the at-dir node's share
        val (explorerNode, restNode) = ImGui.dockBuilderSplitNode(dockId, ImGuiDir.LEFT, explorerRatio)
        val (outputNode, midNode) = ImGui.dockBuilderSplitNode(restNode, ImGuiDir.DOWN, outputRatio)
        // Editors get the wide side; Structure is a slim outline column.
        val (structureNode, editorsNode) = ImGui.dockBuilderSplitNode(midNode, ImGuiDir.RIGHT, 0.22f)
        ImGui.dockBuilderDockWindow("Explorer##ws-${dir}", explorerNode)
        ImGui.dockBuilderDockWindow("Editors##ws-${dir}", editorsNode)
        ImGui.dockBuilderDockWindow("Structure##ws-${dir}", structureNode)
        ImGui.dockBuilderDockWindow("Output##ws-${dir}", outputNode)
        ImGui.dockBuilderFinish(dockId)
        dockExplorer = explorerNode
        dockEditors = editorsNode
        dockOutput = outputNode
        dockStructure = structureNode
    }

    private fun renderExplorerPane() {
        if (dockExplorer == 0) return
        if (ImGui.begin("Explorer##ws-${dir}", null, 0)) {
            if (ImGui.isWindowFocused()) paneFocused = true
            explorer.lastReveal = coreRef.activeDoc()?.path
            explorer.draw()
        }
        ImGui.end()
    }

    private fun renderEditorsPane(core: AppCore) {
        if (dockEditors == 0) return
        if (ImGui.begin("Editors##ws-${dir}", null, 0)) {
            if (ImGui.isWindowFocused()) paneFocused = true
            renderEditors(core, ImGui.getContentRegionAvail().x)
        }
        ImGui.end()
    }

    private fun renderOutputPane() {
        if (dockOutput == 0) return
        if (cn.enaium.imcode.editor.AutoDebug) {
            cn.enaium.imcode.app.OutputLog.info("Auto", "output pane (docked)")
        }
        if (ImGui.begin("Output##ws-${dir}", null, 0)) {
            if (ImGui.isWindowFocused()) paneFocused = true
            output.draw()
        }
        ImGui.end()
    }

    private fun renderEditors(core: AppCore, width: Float) {
        val docs = core.documents
        val tabH = ImGui.getFrameHeight()
        val scrollbarH = 12f // room for the horizontal tab scrollbar
        val editorH = (ImGui.getContentRegionAvail().y - tabH - scrollbarH - 10f).coerceAtLeast(40f)
        val noScroll = ImGuiWindowFlags.NO_SCROLLBAR or ImGuiWindowFlags.NO_SCROLL_WITH_MOUSE

        // tab row: custom strip — selection is owned by activeFile alone.
        // Many tabs overflow into a horizontal scrollbar (drawn BELOW the
        // tabs, hence the extra height); nothing is shrunk.
        if (ImGui.beginChild("##tabbar-${dir}", ImVec2(width, tabH + scrollbarH), 0, ImGuiWindowFlags.HORIZONTAL_SCROLLBAR)) {
            var clicked: String? = null
            var closeClicked: String? = null
            // Scroll the tab bar to the newly activated tab exactly once
            // (when the active file changes), so selecting a file from the
            // explorer never leaves its tab hidden behind the overflow.
            val activeChanged = activeFile != lastActiveFile
            if (activeChanged) lastActiveFile = activeFile
            for (path in openTabs.toList()) {
                val doc = docs.get(path) ?: continue
                val selected = path == activeFile
                val label = doc.name + if (doc.dirty) " *" else ""
                if (selected) {
                    // Stronger active-tab styling: brighter fill + top accent
                    // line, VS Code style.
                    ImGui.pushStyleColor(ImGuiCol.BUTTON, ImGui.colorConvertU32ToFloat4(0xFF2E4A62.toInt()))
                    ImGui.pushStyleColor(ImGuiCol.BUTTON_HOVERED, ImGui.colorConvertU32ToFloat4(0xFF3A5A76.toInt()))
                    ImGui.pushStyleColor(ImGuiCol.BUTTON_ACTIVE, ImGui.colorConvertU32ToFloat4(0xFF3A5A76.toInt()))
                }
                if (ImGui.button(label + "##tab-" + path)) clicked = path
                if (ImGui.isItemHovered()) ImGui.setTooltip(doc.path)
                if (selected && activeChanged) {
                    // Bring the selected tab into view (0.5 = center it).
                    ImGui.setScrollHereX(0.5f)
                }
                if (selected) {
                    // Accent bar on the TOP edge of the active tab (drawn
                    // inside the button rect): the bottom edge collides with
                    // the horizontal scrollbar strip when tabs overflow,
                    // which visually "covers" the highlight.
                    val min = ImGui.getItemRectMin()
                    val max = ImGui.getItemRectMax()
                    ImGui.getWindowDrawList().DrawRectFilled(
                        ImVec2(min.x + 1f, min.y),
                        ImVec2(max.x - 1f, min.y + 2f),
                        0xFF4FC1FF.toInt(), // AABBGGRR accent
                    )
                    ImGui.popStyleColor(3)
                }
                // The context-menu body MUST be rendered immediately inside
                // beginPopupContextItem's true branch (same item, same
                // frame): deferring it until after the loop would let every
                // later tab's button become part of this popup.
                if (ImGui.beginPopupContextItem("##tabmenu-" + path)) {
                    if (ImGui.menuItem("Close")) requestClose(path)
                    if (ImGui.menuItem("Close Others")) closeTabsExcept(path)
                    if (ImGui.menuItem("Close All")) closeTabsExcept()
                    if (ImGui.menuItem("Close Left")) closeTabsLeftOf(path)
                    if (ImGui.menuItem("Close Right")) closeTabsRightOf(path)
                    if (ImGui.menuItem("Close Unmodified")) closeUnmodified()
                    ImGui.endPopup()
                }
                // per-tab close "x" on the SAME line as the tab label
                ImGui.sameLine(0f, 2f)
                if (ImGui.smallButton("x##close-" + path)) closeClicked = path
                ImGui.sameLine()
            }
            if (clicked != null) activeFile = clicked
            if (closeClicked != null) requestClose(closeClicked)
        }
        ImGui.endChild()


        // editor body: the only scrollable part (its own scrollbars)
        if (ImGui.beginChild("##editor-body-${dir}", ImVec2(width, editorH), ImGuiChildFlags.BORDERS, noScroll)) {
            val doc = activeFile?.let { docs.get(it) }
            if (doc != null) {
                if (doc.isBinary) {
                    // Binary files render as a read-only hex view.
                    val me = doc.hexEditor
                    if (me != null) {
                        cn.enaium.imgui.extensions.memoryeditor.MemoryEditor.drawContents(
                            me,
                            doc.binaryBytes,
                        )
                    } else {
                        ImGui.textDisabled("  Binary file (no hex editor available).")
                    }
                } else {
                    maybeRequestStructure(core, doc)
                    maybeRequestCodeLens(core, doc)
                    maybeRequestFolding(core, doc)
                    maybeRequestInlayHints(core, doc)
                    maybeRequestSignature(core, doc)
                    doc.editor.render("##editor-${doc.path}", ImVec2(-1f, -1f))
                    renderLspOverlays(core, doc)
                }
            } else {
                ImGui.textDisabled("  No file open. Use the explorer or Ctrl+O.")
            }
        }
        ImGui.endChild()

        // remember the editor's screen rect: the completion popup positions
        // itself against the editor body and never leaves its area
        val rectMin = ImGui.getItemRectMin()
        val rectMax = ImGui.getItemRectMax()
        editorBodyRectMin = rectMin
        editorBodyRectMax = rectMax
    }

    private fun clipText(text: String, maxWidth: Float): String {
        if (ImGui.calcTextSize(text).x <= maxWidth) return text
        val sb = StringBuilder()
        var w = 0f
        for (ch in text) {
            val cw = ImGui.calcTextSize(ch.toString()).x
            if (w + cw > maxWidth - 8f) break
            sb.append(ch)
            w += cw
        }
        return sb.toString() + "…"
    }

    private fun kindColor(kind: Int?): Int = when (kind) {
        2, 3, 18, 24 -> 0xFFC678DD.toInt() // method/function/ref/operator
        5, 7, 8, 22, 13, 25 -> 0xFFE5C07B.toInt() // field/class/interface/struct/enum/typeparam
        4, 6, 10, 21, 20, 12 -> 0xFF56B6C2.toInt() // value kinds
        14, 1 -> 0xFFD19A66.toInt() // keyword/text
        15 -> 0xFF78DCE8.toInt() // snippet
        else -> 0xFFABB2BF.toInt()
    }

    private fun renderUnsavedModal(core: AppCore, path: String) {
        val doc = core.documents.get(path) ?: run { pendingClosePath = null; return }
        if (openModalOnce) {
            ImGui.openPopup("Unsaved Changes")
            openModalOnce = false
        }
        if (ImGui.beginPopupModal("Unsaved Changes", null, ImGuiWindowFlags.ALWAYS_AUTO_RESIZE)) {
            ImGui.text("'${doc.name}' has unsaved changes.")
            ImGui.text("Save them before closing?")
            ImGui.separator()
            if (ImGui.button("Save", ImVec2(90f, 0f))) {
                core.documents.save(doc)
                closeNow(path)
                pendingClosePath = null
                ImGui.closeCurrentPopup()
            }
            ImGui.sameLine()
            if (ImGui.button("Don't Save", ImVec2(110f, 0f))) {
                closeNow(path)
                pendingClosePath = null
                ImGui.closeCurrentPopup()
            }
            ImGui.sameLine()
            if (ImGui.button("Cancel", ImVec2(90f, 0f))) {
                pendingClosePath = null
                ImGui.closeCurrentPopup()
            }
            ImGui.endPopup()
        }
    }

    private fun renderWorkspaceCloseModal(core: AppCore) {
        if (core.pendingCloseOpenOnce) {
            ImGui.openPopup("Close Workspace")
            core.pendingCloseOpenOnce = false
        }
        if (ImGui.beginPopupModal("Close Workspace", null, ImGuiWindowFlags.ALWAYS_AUTO_RESIZE)) {
            ImGui.text("Close workspace '${title()}'?")
            ImGui.text("Unsaved changes will be saved before closing.")
            ImGui.separator()
            if (ImGui.button("Save & Close", ImVec2(140f, 0f))) {
                core.confirmWorkspaceClose(this, save = true)
                ImGui.closeCurrentPopup()
            }
            ImGui.sameLine()
            if (ImGui.button("Close without saving", ImVec2(180f, 0f))) {
                core.confirmWorkspaceClose(this, save = false)
                ImGui.closeCurrentPopup()
            }
            ImGui.sameLine()
            if (ImGui.button("Cancel", ImVec2(90f, 0f))) {
                core.cancelWorkspaceClose()
                ImGui.closeCurrentPopup()
            }
            ImGui.endPopup()
        }
    }

    private fun renderNewFileModal(core: AppCore) {
        if (newFileOpen[0] && newFileName == null) newFileName = ""
        if (newFileOpen[0] && !wasOpenOnce) {
            ImGui.openPopup("New File")
            wasOpenOnce = true
        }
        if (ImGui.beginPopupModal("New File", null, ImGuiWindowFlags.ALWAYS_AUTO_RESIZE)) {
            ImGui.text("Create in: $dir")
            ImGui.setNextItemWidth(360f)
            newFileName = ImGui.inputText("Name##newfile", newFileName ?: "") ?: newFileName
            if (ImGui.button("Create", ImVec2(90f, 0f))) {
                val name = newFileName?.trim()
                if (!name.isNullOrEmpty()) {
                    core.documents.createFile(dir, name) { path -> core.openFileInWorkspace(this, path) }
                }
                newFileOpen[0] = false
                newFileName = null
                wasOpenOnce = false
                ImGui.closeCurrentPopup()
            }
            ImGui.sameLine()
            if (ImGui.button("Cancel", ImVec2(90f, 0f))) {
                newFileOpen[0] = false
                newFileName = null
                wasOpenOnce = false
                ImGui.closeCurrentPopup()
            }
            ImGui.endPopup()
        }
    }

    private var wasOpenOnce = false

    // ==================== LSP-driven UI ====================

    /** Fires documentSymbol once per file activation (debounced by [structureRequested]). */
    private fun maybeRequestStructure(core: AppCore, doc: Document) {
        if (structureFile != doc.path) {
            structureFile = doc.path
            structureSymbols = emptyList()
            structureRequested = false
            structureKey = null
            structureRetries = 0
            codeLensRequested = false
            codeLensKey = null
            codeLensRetries = 0
            foldingRequested = false
            foldingKey = null
            foldingRetries = 0
            inlayHintsRequested = false
            inlayHintsKey = null
            inlayHintsRetries = 0
        }
        if (structureRequested) return
        // Request once per (file, document version): without the key check
        // every render re-fired documentSymbol (a request flood that also
        // starved semantic tokens). Throttled so edit storms do not queue
        // one request per keystroke.
        val key = doc.path to doc.version
        if (structureKey == key) return
        val now = cn.enaium.imcode.platform.Platform.currentTimeMillis()
        if (now - lastStructureRequestMs < 400) return
        lastStructureRequestMs = now
        structureRequested = true
        core.lsp.requestDocumentSymbols(doc) { symbols ->
            structureRequested = false
            if (symbols.isNotEmpty()) {
                if (structureFile == doc.path) structureSymbols = symbols
                structureKey = key
                structureRetries = 0
            } else if (core.lsp.matchingClient(doc) == null) {
                // Server still starting (kotlin-lsp takes seconds to index):
                // poll again after the throttle without burning retries —
                // otherwise the budget is exhausted before it is running and
                // the pane stays empty forever.
            } else if (structureRetries < 3) {
                structureRetries++
            } else {
                structureKey = key
            }
        }
    }

    /** Fires codeLens once per file activation. */
    private fun maybeRequestCodeLens(core: AppCore, doc: Document) {
        if (codeLensRequested) return
        val key = doc.path to doc.version
        if (codeLensKey == key) return
        val now = cn.enaium.imcode.platform.Platform.currentTimeMillis()
        if (now - lastCodeLensRequestMs < 400) return
        lastCodeLensRequestMs = now
        codeLensRequested = true
        core.lsp.requestCodeLens(doc) { lenses ->
            codeLensRequested = false
            if (lenses.isNotEmpty()) {
                core.documents.setCodeLenses(doc, lenses)
                codeLensKey = key
                codeLensRetries = 0
            } else if (core.lsp.matchingClient(doc) == null) {
                // Server still starting: keep polling without burning retries.
            } else if (codeLensRetries < 3) {
                codeLensRetries++
            } else {
                codeLensKey = key
            }
        }
    }

    /** Fires inlayHint once per file version (same retry policy as folding). */
    private fun maybeRequestInlayHints(core: AppCore, doc: Document) {
        if (inlayHintsRequested) return
        val key = doc.path to doc.version
        if (inlayHintsKey == key) return
        val now = cn.enaium.imcode.platform.Platform.currentTimeMillis()
        if (now - lastInlayHintsRequestMs < 400) return
        lastInlayHintsRequestMs = now
        inlayHintsRequested = true
        core.lsp.requestInlayHints(doc) { hints ->
            inlayHintsRequested = false
            if (hints.isNotEmpty()) {
                core.documents.setInlayHints(doc, hints)
                inlayHintsKey = key
                inlayHintsRetries = 0
            } else if (core.lsp.matchingClient(doc) == null) {
                // Server still starting: keep polling without burning retries.
            } else if (inlayHintsRetries < 3) {
                inlayHintsRetries++
            } else {
                inlayHintsKey = key
            }
        }
    }

    /** Fires foldingRange once per file activation (codeLens retry policy). */
    private fun maybeRequestFolding(core: AppCore, doc: Document) {
        if (foldingRequested) return
        val key = doc.path to doc.version
        if (foldingKey == key) return
        val now = cn.enaium.imcode.platform.Platform.currentTimeMillis()
        if (now - lastFoldingRequestMs < 400) return
        lastFoldingRequestMs = now
        foldingRequested = true
        core.lsp.requestFoldingRange(doc) { ranges ->
            foldingRequested = false
            if (ranges.isNotEmpty()) {
                core.documents.setFoldRanges(doc, ranges)
                foldingKey = key
                foldingRetries = 0
            } else if (core.lsp.matchingClient(doc) == null) {
                // Server still starting: keep polling without burning retries.
            } else if (foldingRetries < 3) {
                foldingRetries++
            } else {
                foldingKey = key
            }
        }
    }

    /**
     * Requests signature help while the caret sits inside a call's parens
     * (the line's current character is an open paren or the last typed char
     * was one). Debounced by [signatureActive]/[signatureRequested].
     */
    private fun maybeRequestSignature(core: AppCore, doc: Document) {
        if (signatureRequested) return
        val editor = doc.editor
        val lineText = editor.buffer.line(editor.cursor.line)
        val idx = editor.cursor.index
        val prev = lineText.getOrNull(idx - 1)
        val cur = lineText.getOrNull(idx)
        val inCall = prev == '(' || cur == ')' || cur == '(' || prev == ','
        if (!inCall) {
            signatureHelp = null
            signatureActive = false
            signatureKey = null
            return
        }
        // One request per (file, version, caret position): re-requesting the
        // same spot every frame was the other half of the request flood.
        val key = Quad(doc.path, doc.version, editor.cursor.line, idx)
        if (signatureKey == key) return
        val now = cn.enaium.imcode.platform.Platform.currentTimeMillis()
        if (now - lastSignatureRequestMs < 300) return
        lastSignatureRequestMs = now
        signatureKey = key
        signatureRequested = true
        signatureActive = true
        core.lsp.requestSignatureHelp(doc, Position(editor.cursor.line, idx)) { help ->
            signatureHelp = help
            signatureRequested = false
        }
    }

    /** Renders completion/hover/signature overlays after the editor. */
    private fun renderLspOverlays(core: AppCore, doc: Document) {
        renderCompletionPopup(core, doc)
        renderHoverPopup(core, doc)
        if (signatureActive && signatureHelp != null) {
            renderSignaturePopup(doc)
        }
        // Navigate to a symbol/definition target picked from the structure
        // tree or the references panel.
        val nav = pendingStructureNav
        if (nav != null) {
            pendingStructureNav = null
            core.openLocation(nav.first, nav.second, nav.third)
        }
    }

    /** Signature help popup above the caret. */
    private fun renderSignaturePopup(doc: Document) {
        val h = signatureHelp ?: return
        if (h.signatures.isEmpty()) {
            signatureActive = false
            return
        }
        val sig = h.signatures.getOrNull(h.activeSignature ?: 0) ?: return
        val editor = doc.editor
        val x = editor.caretScreenX()
        val y = editor.caretScreenY()
        ImGui.setNextWindowPos(ImVec2(x, y - editor.lineHeightPx() * 2.2f))
        ImGui.setNextWindowSizeConstraints(ImVec2(340f, 0f), ImVec2(640f, ImGui.getIO().displaySize.y * 0.4f))
        ImGui.begin(
            "##signature-${dir}",
            null,
            ImGuiWindowFlags.NO_TITLE_BAR or ImGuiWindowFlags.NO_RESIZE or ImGuiWindowFlags.NO_MOVE or
                ImGuiWindowFlags.NO_FOCUS_ON_APPEARING or ImGuiWindowFlags.NO_NAV_FOCUS or
                ImGuiWindowFlags.NO_SCROLLBAR or ImGuiWindowFlags.NO_SCROLL_WITH_MOUSE,
        )
        // Highlight the active parameter inside the label.
        val label = sig.label
        val activeParam = h.activeParameter ?: sig.activeParameter ?: 0
        val paramLabel = sig.parameters?.getOrNull(activeParam)?.let { p ->
            when (val l = p.label) {
                is cn.enaium.lsp.model.ParameterLabel.StringValue -> l.value
                is cn.enaium.lsp.model.ParameterLabel.Offsets -> {
                    val (st, en) = l.value
                    label.substring(st.coerceIn(0, label.length), en.coerceIn(0, label.length))
                }
                else -> null
            }
        }
        if (paramLabel != null && paramLabel.isNotEmpty()) {
            val start = label.indexOf(paramLabel)
            if (start >= 0) {
                ImGui.text(label.substring(0, start))
                ImGui.sameLine(0f, 0f)
                ImGui.pushStyleColor(ImGuiCol.TEXT, ImVec4(0.9f, 0.75f, 0.48f, 1f))
                ImGui.text(paramLabel)
                ImGui.popStyleColor()
                ImGui.sameLine(0f, 0f)
                ImGui.text(label.substring(start + paramLabel.length))
                ImGui.end()
                return
            }
        }
        ImGui.textWrapped(label)
        ImGui.end()
    }

    /** The Structure pane: a tree of the active document's symbols. */
    private fun renderStructurePane(core: AppCore) {
        if (dockStructure == 0) return
        if (ImGui.begin("Structure##ws-${dir}", null, 0)) {
            if (ImGui.isWindowFocused()) paneFocused = true
            val doc = activeFile?.let { core.documents.get(it) }
            if (doc == null) {
                ImGui.textDisabled("  No file open.")
                ImGui.end()
                return
            }
            if (structureSymbols.isEmpty()) {
                ImGui.textDisabled("  No symbols (server may not support documentSymbol).")
                ImGui.end()
                return
            }
            renderSymbolTree(core, doc, structureSymbols)
        }
        ImGui.end()
    }

    /** Real tree (expandable nodes): single click navigates, double click toggles. */
    private fun renderSymbolTree(core: AppCore, doc: Document, symbols: List<DocumentSymbol>) {
        for (sym in symbols) {
            val children = sym.children.orEmpty()
            val label = sym.name + if (sym.detail != null) "  (${sym.detail})" else ""
            val id = "##sym-${dir}-${sym.range.start.line}-${sym.range.start.character}-${sym.name}"
            var flags = ImGuiTreeNodeFlags.SPAN_AVAILABLE_WIDTH or
                ImGuiTreeNodeFlags.OPEN_ON_DOUBLE_CLICK or
                ImGuiTreeNodeFlags.DEFAULT_OPEN
            if (children.isEmpty()) flags = flags or ImGuiTreeNodeFlags.LEAF
            val opened = ImGui.treeNodeEx(label + id, flags)
            if (ImGui.isItemClicked()) {
                // Click: navigate to the symbol's selection range.
                val start = sym.selectionRange.start
                pendingStructureNav = Triple(doc.path, start.line, start.character)
            }
            if (opened) {
                // treePop pairs with `opened`, not with having children:
                // ImGui returns true for leaf nodes too (no collapsed state),
                // so skipping it here triggers "Missing TreePop()".
                if (children.isNotEmpty()) renderSymbolTree(core, doc, children)
                ImGui.treePop()
            }
        }
    }


    /** Completion popup at the caret (lsp-edit style overlay). */
    private fun renderCompletionPopup(core: AppCore, doc: Document) {
        if (!doc.completionActive || doc.completionItems.isEmpty()) return
        val editor = doc.editor
        val x = editor.caretScreenX()
        val y = editor.caretScreenY()
        // Anchor at the caret whenever the popup (re)appears; afterwards the
        // user may move/resize both windows like any other ImGui window.
        ImGui.setNextWindowPos(ImVec2(x, y + editor.lineHeightPx()), cn.enaium.imgui.ImGuiCond.APPEARING)
        // Explicit height: a 0 component makes ImGui auto-fit that axis,
        // which then overrides whatever the user resizes to.
        ImGui.setNextWindowSize(ImVec2(340f, 240f), cn.enaium.imgui.ImGuiCond.APPEARING)
        ImGui.setNextWindowSizeConstraints(
            ImVec2(200f, 60f),
            ImVec2(1400f, ImGui.getIO().displaySize.y * 0.8f),
        )
        ImGui.begin(
            "##completion-${dir}",
            null,
            ImGuiWindowFlags.NO_TITLE_BAR or
                ImGuiWindowFlags.NO_FOCUS_ON_APPEARING or ImGuiWindowFlags.NO_NAV_FOCUS,
        )
        // Keyboard navigation (the editor leaves arrows/Enter/Tab to the
        // popup while it is open via keysReservedByOverlay).
        val items = doc.completionItems
        val upPressed = ImGui.isKeyPressed(cn.enaium.imgui.ImGuiKey.UP_ARROW)
        val downPressed = ImGui.isKeyPressed(cn.enaium.imgui.ImGuiKey.DOWN_ARROW)
        if (upPressed) {
            doc.completionSelected = (doc.completionSelected - 1 + items.size) % items.size
        }
        if (downPressed) {
            doc.completionSelected = (doc.completionSelected + 1) % items.size
        }
        if (ImGui.isKeyPressed(cn.enaium.imgui.ImGuiKey.ENTER) || ImGui.isKeyPressed(cn.enaium.imgui.ImGuiKey.TAB)) {
            core.documents.acceptCompletion(doc, doc.completionSelected)
            ImGui.end()
            return
        }
        if (ImGui.isKeyPressed(cn.enaium.imgui.ImGuiKey.ESCAPE)) {
            doc.completionActive = false
            doc.completionItems = emptyList()
            ImGui.end()
            return
        }
        // Fill the server-deferred fields (documentation, full labelDetails)
        // a few rows per frame while the popup is open.
        core.documents.resolveCompletionRows(doc)
        val maxItems = 20
        val count = minOf(items.size, maxItems)
        for (i in 0 until count) {
            val row = items[i]
            val selected = i == doc.completionSelected
            if (ImGui.selectable(row.label + "##c-${dir}-${i}", selected)) {
                core.documents.acceptCompletion(doc, i)
                ImGui.end()
                return
            }
            val rowMin = ImGui.getItemRectMin()
            val rowMax = ImGui.getItemRectMax()
            // Grey, non-interactive suffix: the signature detail right after
            // the label.
            if (row.detail.isNotEmpty()) {
                ImGui.sameLine(0f, 6f)
                ImGui.textDisabled(row.detail)
            }
            // labelDetails.description (the type) goes right-aligned
            // at the row's end. Layout-based (setCursorPosX) rather than a
            // draw-list overlay so it participates in clipping/DPI normally.
            if (row.trailing.isNotEmpty()) {
                // Drawn as an overlay in screen coordinates (never part of
                // the item flow), so dropping it can neither need a sameLine
                // nor disturb the next row's wrapping.
                //
                // The space check must use the LABEL's text width, not
                // getItemRectMax: ImGui's Selectable() spans the full row by
                // default, so its rect right edge is the row end (the check
                // never passed and the column was never drawn).
                val tw = ImGui.calcTextSize(row.trailing).x
                val textEnd = ImGui.getWindowPos().x + 8f +
                    ImGui.calcTextSize(row.label).x +
                    if (row.detail.isNotEmpty()) 6f + ImGui.calcTextSize(row.detail).x else 0f
                val right = ImGui.getWindowPos().x + ImGui.getWindowSize().x - 8f
                if (right - tw > textEnd + 12f) {
                    val y = rowMin.y + (ImGui.getFrameHeight() - ImGui.getTextLineHeight()) * 0.5f
                    ImGui.getWindowDrawList().DrawText(
                        ImVec2(right - tw, y),
                        row.trailing,
                        ImGui.getColorU32(cn.enaium.imgui.ImGuiCol.TEXT_DISABLED),
                    )
                }
            }
            // Keep the selection visible while arrowing (UP used to leave it
            // scrolled out of view).
            if (selected && (upPressed || downPressed)) ImGui.setScrollHereY(0.5f)
        }
        val listPos = ImGui.getWindowPos()
        val listSize = ImGui.getWindowSize()
        val listMax = ImVec2(listPos.x + listSize.x, listPos.y + listSize.y)
        ImGui.end()

        // Side panel: the selected item's documentation (markdown), in its
        // own resizable window to the right of the list. Hidden when the
        // item has none — labelDetails.description is a qualifier, not docs.
        val sel = items.getOrNull(doc.completionSelected)
        var docPos: ImVec2? = null
        var docMax: ImVec2? = null
        if (sel != null && sel.doc.isNotBlank()) {
            // Position tracks the list window (it can be moved/resized);
            // size stays APPEARING so user resizes are not overridden.
            ImGui.setNextWindowPos(
                ImVec2(listPos.x + listSize.x + 4f, listPos.y),
                cn.enaium.imgui.ImGuiCond.ALWAYS,
            )
            ImGui.setNextWindowSize(ImVec2(380f, 280f), cn.enaium.imgui.ImGuiCond.APPEARING)
            ImGui.setNextWindowSizeConstraints(
                ImVec2(220f, 80f),
                ImVec2(1600f, ImGui.getIO().displaySize.y * 0.8f),
            )
            ImGui.begin(
                "##completion-doc-${dir}",
                null,
                ImGuiWindowFlags.NO_TITLE_BAR or
                    ImGuiWindowFlags.NO_FOCUS_ON_APPEARING or ImGuiWindowFlags.NO_NAV_FOCUS,
            )
            val dp = ImGui.getWindowPos()
            val ds = ImGui.getWindowSize()
            docPos = dp
            docMax = ImVec2(dp.x + ds.x, dp.y + ds.y)
            cn.enaium.lsp.edit.MarkdownCode.render(
                core.markdownConfig,
                sel.doc,
                language = doc.editor.language,
                palette = doc.editor.palette,
            )
            ImGui.end()
        }

        // Click outside either window dismisses the popup. Positions come
        // from this frame's rects, outset a little: ImGui's resize border
        // sits just outside the rect, and clicking it to resize must not
        // close the popup (that made the corner grip vanish the window).
        val mouse = ImGui.getMousePos()
        fun outside(min: ImVec2, max: ImVec2): Boolean {
            val pad = 8f
            return mouse.x < min.x - pad || mouse.x > max.x + pad ||
                mouse.y < min.y - pad || mouse.y > max.y + pad
        }
        val outsideList = outside(listPos, listMax)
        val outsideDoc = docPos == null || docMax == null || outside(docPos!!, docMax!!)
        if (ImGui.isMouseClicked(0) && outsideList && outsideDoc) {
            doc.completionActive = false
            doc.completionItems = emptyList()
        }
    }

    /**
     * Last frame's hover-popup rect (screen coords). The pointer entering
     * the popup must keep it open, but the window's hover state is only
     * known after it renders — a frame stale rect lets the check run first.
     */
    private var hoverWindowMin: ImVec2? = null
    private var hoverWindowMax: ImVec2? = null

    /**
     * Hover popup from the cached LSP hover text. A regular (non-tooltip)
     * window so the pointer can move onto it: while hovered it stays open
     * even though the editor already ended the code hover, and it can be
     * resized with the mouse. Closing happens here — when the pointer is on
     * neither the code nor the popup.
     */
    private fun renderHoverPopup(core: AppCore, doc: Document) {
        val text = doc.hoverText
        val mouse = ImGui.getMousePos()
        val onPopup = hoverWindowMin?.let { min ->
            val max = hoverWindowMax ?: min
            mouse.x >= min.x && mouse.x <= max.x && mouse.y >= min.y && mouse.y <= max.y
        } ?: false
        if (text.isNullOrBlank() || (!doc.hoverActive && !onPopup)) {
            if (text != null) doc.hoverText = null
            hoverWindowMin = null
            hoverWindowMax = null
            return
        }
        ImGui.setNextWindowPos(ImGui.getMousePos(), cn.enaium.imgui.ImGuiCond.APPEARING)
        ImGui.setNextWindowSize(ImVec2(440f, 0f), cn.enaium.imgui.ImGuiCond.APPEARING)
        ImGui.setNextWindowSizeConstraints(
            ImVec2(260f, 60f),
            ImVec2(1600f, ImGui.getIO().displaySize.y * 0.9f),
        )
        ImGui.begin(
            "##hover-${dir}",
            null,
            ImGuiWindowFlags.NO_TITLE_BAR or
                ImGuiWindowFlags.NO_FOCUS_ON_APPEARING or ImGuiWindowFlags.NO_NAV_FOCUS,
        )
        // Markdown rendering with fenced code blocks (read-only highlighted
        // via MarkdownCode) and inline-code chips, like the lsp-edit hover.
        cn.enaium.lsp.edit.MarkdownCode.render(
            core.markdownConfig,
            text,
            language = doc.editor.language,
            palette = doc.editor.palette,
        )
        val pos = ImGui.getWindowPos()
        val size = ImGui.getWindowSize()
        hoverWindowMin = pos
        hoverWindowMax = ImVec2(pos.x + size.x, pos.y + size.y)
        ImGui.end()
    }

    // ==================== rename / references (host UI) ====================

    /** Opens the rename dialog for the symbol under the caret. */
    fun openRename(core: AppCore) {
        val doc = core.activeDoc() ?: return
        val pos = doc.editor.cursor
        val wordStart = doc.editor.buffer.line(pos.line)
            .substring(0, pos.index).takeLastWhile { it.isLetterOrDigit() || it == '_' }
        renameTarget = pos.line to pos.index
        renameText = wordStart
        renameOpen = true
        renamePopupCursor = pos
        renameFocusInput = true
        // beginPopupModal only renders once the popup has been opened —
        // without this the dialog never appeared (the menu item looked dead).
        ImGui.openPopup("Rename Symbol")
    }

    fun renderRenameModal(core: AppCore) {
        if (!renameOpen) return
        if (ImGui.beginPopupModal("Rename Symbol", null, ImGuiWindowFlags.ALWAYS_AUTO_RESIZE)) {
            val doc = core.activeDoc()
            // The popup does not block ImGui key polling, so arrows still
            // move the caret; leaving the symbol (or Esc) exits edit mode.
            val caretLeft = doc == null || doc.editor.cursor != renamePopupCursor
            if (ImGui.isKeyPressed(cn.enaium.imgui.ImGuiKey.ESCAPE) || caretLeft) {
                renameOpen = false
                ImGui.closeCurrentPopup()
                ImGui.endPopup()
                return
            }
            ImGui.text("New name:")
            ImGui.setNextItemWidth(320f)
            if (renameFocusInput) ImGui.setKeyboardFocusHere()
            renameText = ImGui.inputText("##rename-${dir}", renameText ?: "") ?: renameText
            renameFocusInput = false
            if (ImGui.button("Rename", ImVec2(90f, 0f))) {
                val newName = renameText?.trim()
                if (newName != null && newName.isNotEmpty() && doc != null && renameTarget != null) {
                    val (line, idx) = renameTarget!!
                    core.lsp.requestRename(doc, Position(line, idx), newName) { edit ->
                        if (edit != null) core.applyWorkspaceEditPublic(edit)
                    }
                }
                renameOpen = false
                ImGui.closeCurrentPopup()
            }
            ImGui.sameLine()
            if (ImGui.button("Cancel", ImVec2(90f, 0f))) {
                renameOpen = false
                ImGui.closeCurrentPopup()
            }
            ImGui.endPopup()
        }
    }

    /** Requests code actions for the caret/selection and shows the list. */
    fun openCodeActions(core: AppCore) {
        val doc = core.activeDoc() ?: return
        val editor = doc.editor
        val sel = editor.selectionBounds()
        val range = if (sel != null) {
            Position(sel.first.line, sel.first.index) to Position(sel.second.line, sel.second.index)
        } else {
            val line = editor.cursor.line
            Position(line, 0) to Position(line, editor.buffer.line(line).length)
        }
        // The server computes quick fixes from the diagnostics overlapping
        // the requested range.
        val diagnostics = doc.diagnostics.filter { d ->
            d.range.start.line <= range.second.line && d.range.end.line >= range.first.line
        }
        codeActionDoc = doc
        core.lsp.requestCodeActions(
            doc,
            cn.enaium.lsp.model.Range(range.first, range.second),
            diagnostics,
        ) { actions ->
            codeActions = actions
            codeActionsOpen = true
        }
    }

    private fun renderCodeActionsWindow(core: AppCore) {
        val openArr = BooleanArray(1) { codeActionsOpen }
        ImGui.setNextWindowSize(ImVec2(440f, 320f), cn.enaium.imgui.ImGuiCond.ONCE)
        if (ImGui.begin("Code Actions##ws-${dir}", openArr, ImGuiWindowFlags.NO_COLLAPSE)) {
            if (!openArr[0]) codeActionsOpen = false
            if (codeActions.isEmpty()) {
                ImGui.textDisabled("  No code actions available.")
            } else {
                for ((i, action) in codeActions.withIndex()) {
                    val title = action.title + if (action.isPreferred == true) "  (preferred)" else ""
                    val enabled = action.disabled == null
                    if (ImGui.selectable(title + "##ca-${dir}-$i", false) && enabled) {
                        applyCodeAction(core, action)
                        codeActionsOpen = false
                    }
                }
            }
        }
        ImGui.end()
    }

    /** Applies a code action: its edit, its command, or resolve-then-apply. */
    private fun applyCodeAction(core: AppCore, action: cn.enaium.lsp.model.CodeAction) {
        val edit = action.edit
        if (edit != null) {
            core.applyWorkspaceEditPublic(edit)
            return
        }
        val cmd = action.command
        if (cmd != null) {
            val doc = codeActionDoc ?: core.activeDoc() ?: return
            core.lsp.requestExecuteCommand(doc, cmd.command, cmd.arguments) { }
            return
        }
        // Lazily-filled action: resolve, then apply whatever came back.
        val doc = codeActionDoc ?: core.activeDoc() ?: return
        if (action.data != null) {
            core.lsp.requestCodeActionResolve(doc, action) { resolved ->
                if (resolved != null) applyCodeAction(core, resolved)
            }
        }
    }

    /** Opens the references panel for the symbol under the caret. */
    fun openReferences(core: AppCore) {
        val doc = core.activeDoc() ?: return
        val pos = doc.editor.cursor
        referencesOpen = true
        core.lsp.requestReferences(doc, Position(pos.line, pos.index)) { refs ->
            referencesList = refs
        }
    }

    fun renderReferencesWindow(core: AppCore) {
        if (!referencesOpen) return
        ImGui.setNextWindowSize(ImVec2(420f, 320f), cn.enaium.imgui.ImGuiCond.ONCE)
        val openArr = BooleanArray(1) { referencesOpen }
        if (ImGui.begin("References##ws-${dir}", openArr, ImGuiWindowFlags.NO_COLLAPSE)) {
            if (!openArr[0]) referencesOpen = false
            if (referencesList.isEmpty()) {
                ImGui.textDisabled("  No references found.")
            } else {
                for (loc in referencesList) {
                    val label = "${core.documents.get(pathFromUri(loc.uri))?.name ?: loc.uri} : ${loc.range.start.line + 1}"
                    // Column included: two references can sit on one line.
                    if (ImGui.selectable(label + "##ref-${loc.uri}-${loc.range.start.line}-${loc.range.start.character}")) {
                        pendingStructureNav = Triple(
                            pathFromUri(loc.uri),
                            loc.range.start.line,
                            loc.range.start.character,
                        )
                        referencesOpen = false
                    }
                }
            }
        }
        ImGui.end()
    }

    private fun pathFromUri(uri: String): String =
        cn.enaium.imcode.util.Uri.uriToPath(uri) ?: uri

}

/** Tiny 4-tuple for request-key tracking. */
private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)