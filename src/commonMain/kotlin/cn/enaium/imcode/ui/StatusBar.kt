package cn.enaium.imcode.ui

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiCond
import cn.enaium.imgui.ImGuiWindowFlags
import cn.enaium.imgui.ImVec2
import cn.enaium.imcode.app.AppCore
import cn.enaium.imcode.platform.ioFile
import cn.enaium.xicons.imgui.icons.intellij.ExpuiGeneralCloseSmallDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiToolwindowsNotificationsDark

/**
 * The status bar along the bottom of a window, IntelliJ style: the path of the
 * open file as a breadcrumb on the left, cursor position, encoding, indent and
 * diagnostics on the right, with the progress and notification indicators
 * next to them.
 */
internal object StatusBar {

    /** Height of the bar; derived from the font so it scales with it. */
    fun height(): Float = ImGui.getFrameHeight() + 8f

    private const val GAP = 12f

    /**
     * Draws the bar across the bottom of the window being drawn.
     *
     * A window of its own rather than a child: the bar belongs to the window's
     * frame, and a child submitted at the top level (outside any window) is
     * attached to ImGui's internal fallback window — which then shows up as a
     * stray "Debug" window holding the status bar.
     */
    fun draw(core: AppCore, ws: WorkspaceWindow?) {
        // The servers' work-done progress is the source of the running jobs.
        ProgressCenter.syncLsp(core.lsp.activeProgress())
        val display = ImGui.getIO().displaySize
        val h = height()
        ImGui.setNextWindowPos(ImVec2(0f, display.y - h), ImGuiCond.ALWAYS)
        ImGui.setNextWindowSize(ImVec2(display.x, h), ImGuiCond.ALWAYS)
        val flags = ImGuiWindowFlags.NO_TITLE_BAR or ImGuiWindowFlags.NO_MOVE or ImGuiWindowFlags.NO_RESIZE or
            ImGuiWindowFlags.NO_SCROLLBAR or ImGuiWindowFlags.NO_SCROLL_WITH_MOUSE or
            ImGuiWindowFlags.NO_SAVED_SETTINGS or ImGuiWindowFlags.NO_DOCKING or
            ImGuiWindowFlags.NO_FOCUS_ON_APPEARING or ImGuiWindowFlags.NO_NAV_FOCUS or
            ImGuiWindowFlags.NO_BRING_TO_FRONT_ON_FOCUS
        if (!ImGui.begin("##statusbar", null, flags)) {
            ImGui.end()
            return
        }
        val right = rightGroup(core, ws)
        val limit = display.x - right - GAP
        breadcrumb(core, ws, limit)
        ImGui.sameLine()
        ImGui.setCursorPosX(display.x - right - GAP)
        drawRightGroup(core, ws)
        ImGui.end()
    }

    // ==================== left: breadcrumb ====================

    /**
     * The open file's path from the workspace root, each segment with its
     * icon: `project > src > main > Foo.kt`. Segments are dropped once they
     * would run into the right group, oldest first, so the file name — the
     * part that matters — always stays.
     */
    private fun breadcrumb(core: AppCore, ws: WorkspaceWindow?, limit: Float) {
        val path = ws?.activeFile ?: return
        val segments = breadcrumbSegments(ws.dir, path)
        val folder = FileIcons.folder
        val startX = ImGui.getCursorPosX()
        for ((index, segment) in segments.withIndex()) {
            val last = index == segments.lastIndex
            val text = IconLayout.spacesFor(IconLayout.gutter()) + segment
            val width = ImGui.calcTextSize(text).x + if (index > 0) ImGui.calcTextSize(" > ").x else 0f
            if (index > 0 && ImGui.getCursorPosX() + width > limit) {
                ImGui.sameLine()
                ImGui.textDisabled(" > ...")
                return
            }
            if (index > 0) {
                ImGui.sameLine()
                ImGui.textDisabled(">")
                ImGui.sameLine()
            }
            if (last) {
                ImGui.text(text)
            } else {
                ImGui.textDisabled(text)
            }
            IconLayout.drawCentered(
                if (last) FileIcons.forFile(segment) else folder,
                ImGui.getItemRectMin(),
                ImGui.getItemRectMax(),
            )
            // the icon gutter is spaces, so the row still starts at startX
            if (ImGui.getCursorPosX() < startX) ImGui.setCursorPosX(startX)
        }
    }

    /**
     * The breadcrumb's labels for [path] under [root]: the root's own name
     * first, then each segment down to the file. A file opened from outside
     * the workspace shows its whole path instead.
     */
    internal fun breadcrumbSegments(root: String, path: String): List<String> {
        val rootFile = ioFile(root)
        val relative = rootFile.relativize(ioFile(path))
        // Path.relativize walks up with ".." when the file is not under the
        // root (a definition opened from a library, say). Showing the file's
        // own path beats claiming it lives under a workspace it does not.
        if (relative.startsWith("/") || relative.startsWith("..")) {
            return ioFile(path).absolutePath.trimStart('/').split('/').filter { it.isNotEmpty() }
        }
        return listOf(rootFile.name.ifEmpty { root }) +
            relative.split('/').filter { it.isNotEmpty() }
    }

    // ==================== right: cursor, encoding, indent ====================

    private fun drawRightGroup(core: AppCore, ws: WorkspaceWindow?) {
        val doc = ws?.activeFile?.let { core.documents.get(it) }
        val items = ArrayList<Pair<String, String?>>()
        if (doc != null) {
            val cursor = doc.editor.cursor
            items += "${cursor.line + 1}:${cursor.index + 1}" to "Cursor position"
            items += "UTF-8" to "File encoding"
            items += indentLabel(core) to "Indent"
            items += "${doc.errorCount}E ${doc.warningCount}W" to "Errors and warnings"
        } else {
            items += "Ready" to null
        }
        // The group was positioned by the caller; the separators go between
        // the items, so the first one stays where it was put. Progress comes
        // first — left of the cursor position — and notifications last.
        var first = true
        if (ProgressCenter.latest() != null) {
            progressIndicator(core)
            first = false
        }
        for (entry in items) {
            if (!first) ImGui.sameLine(0f, GAP)
            first = false
            ImGui.textDisabled(entry.first)
            if (entry.second != null && ImGui.isItemHovered()) ImGui.setTooltip(entry.second!!)
        }
        notificationIndicator(core)
    }

    /** Width of everything on the right, so the breadcrumb can stop before it. */
    private fun rightGroup(core: AppCore, ws: WorkspaceWindow?): Float {
        val doc = ws?.activeFile?.let { core.documents.get(it) }
        val texts = ArrayList<String>()
        if (doc != null) {
            val cursor = doc.editor.cursor
            texts += "${cursor.line + 1}:${cursor.index + 1}"
            texts += "UTF-8"
            texts += indentLabel(core)
            texts += "${doc.errorCount}E ${doc.warningCount}W"
        } else {
            texts += "Ready"
        }
        var width = 0f
        val latest = ProgressCenter.latest()
        if (latest != null) {
            val size = ImGui.getFrameHeight()
            width += ImGui.calcTextSize(latest.title).x + 6f + size * 3.2f + GAP
            if (latest.cancel != null) width += size + GAP
        }
        for (text in texts) width += ImGui.calcTextSize(text).x + GAP
        if (Notifications.pending().isNotEmpty()) width += ImGui.getFrameHeight() + GAP
        return width
    }

    /** "4 spaces" or "Tab", the way IntelliJ names the indent mode. */
    private fun indentLabel(core: AppCore): String {
        val editor = core.config.editor
        return if (editor.insertSpaces) "${editor.tabSize} spaces" else "Tab"
    }

    // ==================== indicators ====================

    /**
     * The latest job: its name on the left, its bar on the right, and a stop
     * button when it can be stopped. Clicking the bar opens the window with
     * every running job.
     */
    private fun progressIndicator(core: AppCore) {
        val task = ProgressCenter.latest() ?: return
        ImGui.textDisabled(task.title)
        if (task.message != null && ImGui.isItemHovered()) ImGui.setTooltip(task.message!!)
        ImGui.sameLine(0f, 6f)
        val size = ImGui.getFrameHeight()
        // Frame-height, like ImGui's own progress bars: a shorter bar clips
        // the percentage it draws inside it.
        ImGui.progressBar(task.fraction ?: -1f, ImVec2(size * 3.2f, size), "")
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(
                task.title + (task.message?.let { " — $it" } ?: "") +
                    (task.fraction?.let { " (${(it * 100).toInt()}%)" } ?: ""),
            )
        }
        if (ImGui.isItemClicked()) core.showProgress = true
        val cancel = task.cancel
        if (cancel != null) {
            if (IconLayout.iconButton("progress-stop-${task.id}", ExpuiGeneralCloseSmallDark, "Stop")) {
                cancel.invoke()
                ProgressCenter.finish(task.id)
            }
        }
    }

    /** Unread notifications; clicking opens the history window. */
    private fun notificationIndicator(core: AppCore) {
        val unread = Notifications.pending()
        if (unread.isEmpty()) return
        ImGui.sameLine(0f, GAP)
        if (IconLayout.iconButton("statusbar-notifications", ExpuiToolwindowsNotificationsDark, "${unread.size} notifications")) {
            core.showNotifications = true
        }
    }
}
