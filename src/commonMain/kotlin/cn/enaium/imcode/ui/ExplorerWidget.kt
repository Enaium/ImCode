package cn.enaium.imcode.ui

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiMouseButton
import cn.enaium.imgui.ImGuiTreeNodeFlags
import cn.enaium.imcode.platform.IoFile
import cn.enaium.imcode.platform.ioFile

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

    /** True while the tree should force every node closed. */
    private var collapseAllRequested = false

    /** File node highlighted as selected (single click); double click opens. */
    private var selectedPath: String? = null

    /** Safe to call from any thread; the tree picks it up on the next frame. */
    fun scheduleRefresh() {
        needsRefresh = true
    }

    /** Expands the tree to reveal [path] and scrolls to it. */
    fun reveal(path: String) {
        revealPath = path
        needsRefresh = true
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
        val expandAll = expandAllRequested
        expandAllRequested = false
        val collapseAll = collapseAllRequested
        collapseAllRequested = false

        // Toolbar actions + context menu (Refresh moved here).
        if (ImGui.button("Locate File")) {
            revealPath = lastReveal
        }
        ImGui.sameLine()
        if (ImGui.button("Expand All")) {
            expandAllRequested = true
        }
        ImGui.sameLine()
        if (ImGui.button("Collapse All")) {
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
                lastReveal?.let { revealPath = it }
            }
            ImGui.endPopup()
        }

        val entry = cache[ioFile(root).absolutePath] ?: return
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

        drawDir(entry, revealChain, reveal, expandAll, collapseAll)
    }

    private fun drawDir(
        entry: Entry,
        revealChain: Set<String>,
        reveal: String?,
        expandAll: Boolean,
        collapseAll: Boolean,
    ) {
        val path = entry.file.absolutePath
        // Single click selects (highlighted), double click toggles — same as
        // the file nodes; the arrow still toggles on a single click.
        val flags = ImGuiTreeNodeFlags.SPAN_AVAILABLE_WIDTH or
            ImGuiTreeNodeFlags.OPEN_ON_DOUBLE_CLICK or
            (if (path == selectedPath) ImGuiTreeNodeFlags.SELECTED else 0)
        val label = entry.file.name.ifEmpty { "/" }
        // Force open/closed for bulk actions or the reveal path. ALWAYS
        // overrides any user-toggle state from the same frame (ONCE only
        // applies when the node's state did not change this frame, which
        // made collapse-all appear to do nothing right after an expand-all).
        if (expandAll || path in revealChain) {
            ImGui.setNextItemOpen(true, cn.enaium.imgui.ImGuiCond.ALWAYS)
        } else if (collapseAll) {
            ImGui.setNextItemOpen(false, cn.enaium.imgui.ImGuiCond.ALWAYS)
        }
        val opened = ImGui.treeNodeEx("$label##$path", flags)
        if (ImGui.isItemClicked()) selectedPath = path
        if (opened) {
            for (child in entry.children) {
                if (child.isDir) {
                    drawDir(child, revealChain, reveal, expandAll, collapseAll)
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
        val isRevealTarget = path == reveal
        if (isRevealTarget) {
            ImGui.setScrollHereY(0.5f)
        }
        // Single click selects; double click opens (IDE convention). The
        // selectable consumes the click, so the double-click check runs on
        // the same item afterwards.
        if (ImGui.selectable(name + "##" + path, path == selectedPath)) {
            selectedPath = path
        }
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
