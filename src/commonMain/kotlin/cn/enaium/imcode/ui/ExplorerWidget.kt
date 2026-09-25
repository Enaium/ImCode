package cn.enaium.imcode.ui

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiMouseButton
import cn.enaium.imgui.ImGuiTreeNodeFlags
import cn.enaium.imcode.platform.IoFile
import cn.enaium.imcode.platform.ioFile
import cn.enaium.xicons.imgui.icons.intellij.ExpuiGeneralCollapseAllDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiGeneralExpandAllDark
import cn.enaium.xicons.imgui.icons.intellij.GeneralLocateDark

/**
 * Directory tree for one workspace. The listing is refreshed into a snapshot
 * so the tree render never touches the file system. Node open state is kept
 * by ImGui itself; single-click toggles folders, single-click selects a file
 * and double-click opens it.
 */
class ExplorerWidget(
    private val root: String,
    private val onOpenFile: (String) -> Unit,
) {
    class Entry(val file: IoFile, val isDir: Boolean, val children: List<Entry>)

    private var cache: Map<String, Entry> = emptyMap()
    @Volatile
    private var needsRefresh = true

    /** When set, the next frame expands the tree down to this file and
     *  scrolls it into view (set by the "Locate File" action). */
    private var revealPath: String? = null

    /** True while the tree should force every node open. */
    private var expandAllRequested = false

    /**
     * Folders the user has open.
     *
     * ImGui keeps node state per id, but a closed parent's children are never
     * submitted, so a bulk action could not reach them: collapse-all closed
     * the root and left every folder below it open. This set is the state the
     * tree renders from, and the bulk actions rewrite it directly.
     */
    private val openPaths = HashSet<String>()

    /** Folders the tree currently has expanded. */
    internal val openFolders: Set<String> get() = openPaths

    /** True while the tree should force every node closed. */
    private var collapseAllRequested = false

    /** File node highlighted as selected (single click); double click opens. */
    private var selectedPath: String? = null

    /** The highlighted row, if any. */
    internal val selected: String? get() = selectedPath

    /** Safe to call from any thread; the tree picks it up on the next frame. */
    fun scheduleRefresh() {
        needsRefresh = true
    }

    /** Expands the tree to [path], selects it and scrolls it into view. */
    fun reveal(path: String) {
        revealPath = path
        selectedPath = path
        needsRefresh = true
    }

    /**
     * Locates the active file: expands the tree to it, makes it the selected
     * node and scrolls it into view.
     *
     * Both the toolbar button and the context menu item go through here —
     * setting only the reveal target expanded the branch but left the previous
     * node highlighted, so the file the action located was not the selected
     * one.
     */
    fun locateActive() {
        lastReveal?.let { reveal(it) }
    }

    /** Expands every directory node (one frame). */
    fun expandAll() {
        expandAllRequested = true
    }

    /** Collapses every directory node (one frame). */
    fun collapseAll() {
        collapseAllRequested = true
    }

    private fun rebuild() {
        val newCache = HashMap<String, Entry>()
        fun walk(dir: IoFile): Entry {
            val children = dir.listFiles()
                ?.filter { it.name != ".git" && it.name != ".gradle" && it.name != "node_modules" && it.name != "build" }
                ?.sortedWith(compareBy<IoFile> { it.isDirectory.not() }.thenBy { it.name.lowercase() })
                ?: emptyList()
            // recurse first so deeper entries carry their real children
            val entries = children.map { child ->
                if (child.isDirectory) walk(child) else Entry(child, isDir = false, children = emptyList())
            }
            val entry = Entry(dir, isDir = true, children = entries)
            newCache[dir.absolutePath] = entry
            return entry
        }
        walk(ioFile(root))
        cache = newCache
        needsRefresh = false
    }

    fun draw() {
        if (needsRefresh) rebuild()

        val reveal = revealPath
        if (reveal != null) revealPath = null

        // Toolbar actions + context menu (Refresh moved here). Icons, not
        // labels: the actions are obvious and the names are long.
        if (IconLayout.iconButton("explorer-locate", GeneralLocateDark, "Locate File")) {
            locateActive()
        }
        ImGui.sameLine()
        if (IconLayout.iconButton("explorer-expand-all", ExpuiGeneralExpandAllDark, "Expand All")) {
            expandAllRequested = true
        }
        ImGui.sameLine()
        if (IconLayout.iconButton("explorer-collapse-all", ExpuiGeneralCollapseAllDark, "Collapse All")) {
            collapseAllRequested = true
        }
        ImGui.separator()

        // Context menu on the tree background: refresh + bulk actions.
        if (ImGui.beginPopupContextWindow("##explorer-ctx", 0)) {
            if (ImGui.menuItem("Refresh")) scheduleRefresh()
            ImGui.separator()
            if (ImGui.menuItem("Expand All")) expandAllRequested = true
            if (ImGui.menuItem("Collapse All")) collapseAllRequested = true
            if (ImGui.menuItem("Locate Active File")) {
                locateActive()
            }
            ImGui.endPopup()
        }

        val entry = cache[ioFile(root).absolutePath] ?: return
        // Bulk actions rewrite the whole tree's state, including folders that
        // are not on screen because their parent is closed.
        if (expandAllRequested) {
            expandAllRequested = false
            setAllOpen(entry, true)
        }
        if (collapseAllRequested) {
            collapseAllRequested = false
            openPaths.clear()
        }
        // Path chain to reveal: root -> ... -> parent of reveal target.
        val revealChain: Set<String> = if (reveal != null) {
            buildSet {
                var p: IoFile? = ioFile(reveal).parent
                while (p != null) {
                    add(p.absolutePath)
                    if (p.absolutePath == ioFile(root).absolutePath) break
                    p = p.parent
                }
            }
        } else emptySet()
        openPaths.addAll(revealChain)

        drawDir(entry, reveal)
    }

    /** Marks every folder in [entry]'s subtree open or closed. */
    private fun setAllOpen(entry: Entry, open: Boolean) {
        if (open) openPaths.add(entry.file.absolutePath) else openPaths.remove(entry.file.absolutePath)
        for (child in entry.children) if (child.isDir) setAllOpen(child, open)
    }

    private fun drawDir(entry: Entry, reveal: String?) {
        val path = entry.file.absolutePath
        // Single click selects (highlighted), double click toggles — same as
        // the file nodes; the arrow still toggles on a single click.
        val flags = ImGuiTreeNodeFlags.SPAN_AVAILABLE_WIDTH or
            ImGuiTreeNodeFlags.OPEN_ON_DOUBLE_CLICK or
            (if (path == selectedPath) ImGuiTreeNodeFlags.SELECTED else 0)
        val label = entry.file.name.ifEmpty { "/" }
        // The state is forced from [openPaths] every frame: ImGui's own
        // storage cannot be the source of truth while closed parents are not
        // submitted (see the note on the field).
        val wantOpen = path in openPaths
        ImGui.setNextItemOpen(wantOpen, cn.enaium.imgui.ImGuiCond.ALWAYS)
        val opened = ImGui.treeNodeEx(
            IconLayout.spacesFor(IconLayout.gutter()) + label + "##$path",
            flags,
        )
        // The arrow owns the leading strip of the node rect; the icon starts
        // just past it (a little clear of the triangle) and is centered on the
        // row like every other icon.
        IconLayout.drawCentered(
            FileIcons.folder,
            ImGui.getItemRectMin(),
            ImGui.getItemRectMax(),
            xOffset = IconLayout.treeLabelOffset(),
        )
        if (ImGui.isItemClicked()) selectedPath = path
        // The arrow (or a double click) flips the node: record it, so the
        // forced state above agrees with what the user just did.
        if (ImGui.isItemToggledOpen()) {
            if (wantOpen) openPaths.remove(path) else openPaths.add(path)
        }
        if (opened) {
            for (child in entry.children) {
                if (child.isDir) {
                    drawDir(child, reveal)
                } else {
                    drawFile(child, reveal)
                }
            }
            ImGui.treePop()
        }
    }

    private fun drawFile(entry: Entry, reveal: String?) {
        val path = entry.file.absolutePath
        val name = entry.file.name
        // Single click selects; double click opens (IDE convention). The
        // selectable consumes the click, so the double-click check runs on
        // the same item afterwards.
        val icon = FileIcons.forFile(name)
        // Icon gutter measured in the active font: the label is indented by
        // whole spaces so the row highlight still spans the full width.
        val gutter = IconLayout.gutter()
        if (ImGui.selectable(IconLayout.spacesFor(gutter) + name + "##" + path, path == selectedPath)) {
            selectedPath = path
        }
        if (path == reveal) {
            // SetScrollHereY scrolls to the item submitted just before it, so
            // it has to follow the selectable: called ahead of it the tree
            // stopped one row short of the file it was locating.
            ImGui.setScrollHereY(0.5f)
        }
        IconLayout.drawCentered(icon, ImGui.getItemRectMin(), ImGui.getItemRectMax())
        if (ImGui.isItemHovered() && ImGui.isMouseDoubleClicked(ImGuiMouseButton.LEFT)) {
            onOpenFile(path)
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(path)
        }
    }

    /** Set by the workspace when the active file changes; used by "Locate File". */
    var lastReveal: String? = null
}
