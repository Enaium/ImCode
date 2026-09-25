package cn.enaium.imcode.ui

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiWindowFlags
import cn.enaium.imgui.ImVec2
import cn.enaium.imcode.app.AppCore
import cn.enaium.imcode.platform.Platform
import cn.enaium.xicons.imgui.Icon
import cn.enaium.xicons.imgui.icons.intellij.ExpuiGeneralCloseSmallDark
import cn.enaium.xicons.imgui.icons.intellij.GeneralError
import cn.enaium.xicons.imgui.icons.intellij.GeneralInformation
import cn.enaium.xicons.imgui.icons.intellij.GeneralWarning

/**
 * Notification balloons and their history window, IntelliJ style.
 *
 * Balloons stack in the bottom-right corner, newest at the bottom, and expire
 * on their own unless the pointer rests on them. The history window is the
 * same list kept around: everything that was ever raised, newest first.
 */
internal object NotificationView {

    private const val WIDTH = 380f
    private const val MARGIN = 12f

    /** How many balloons share the corner before the rest wait in the window. */
    private const val MAX_BALLOONS = 3

    private fun icon(kind: Notifications.Kind): Icon = when (kind) {
        Notifications.Kind.ERROR -> GeneralError
        Notifications.Kind.WARNING -> GeneralWarning
        Notifications.Kind.INFO -> GeneralInformation
    }

    /**
     * Draws the balloons in the bottom-right corner of the current window.
     *
     * Called by the primary window only: a notification is about the app, not
     * about one project, and drawing it per window would multiply it.
     */
    fun drawBalloons(core: AppCore) {
        val display = ImGui.getIO().displaySize
        Notifications.tick(ImGui.getIO().deltaTime)
        val pending = Notifications.pending().take(MAX_BALLOONS)
        if (pending.isEmpty()) return
        // newest at the bottom: walk from the bottom edge upwards
        var bottom = display.y - StatusBar.height() - MARGIN
        for (notice in pending.reversed()) {
            val flags = ImGuiWindowFlags.NO_TITLE_BAR or ImGuiWindowFlags.NO_RESIZE or
                ImGuiWindowFlags.ALWAYS_AUTO_RESIZE or ImGuiWindowFlags.NO_SCROLLBAR or
                ImGuiWindowFlags.NO_SCROLL_WITH_MOUSE or ImGuiWindowFlags.NO_SAVED_SETTINGS or
                ImGuiWindowFlags.NO_DOCKING or ImGuiWindowFlags.NO_FOCUS_ON_APPEARING or
                ImGuiWindowFlags.NO_NAV_FOCUS
            ImGui.setNextWindowPos(ImVec2(display.x - WIDTH - MARGIN, bottom), cn.enaium.imgui.ImGuiCond.ALWAYS)
            ImGui.setNextWindowSize(ImVec2(WIDTH, 0f), cn.enaium.imgui.ImGuiCond.ALWAYS)
            if (ImGui.begin("##notice-${notice.title}-${notice.time}", null, flags)) {
                ImGui.pushTextWrapPos(WIDTH - ImGui.getFrameHeight() - 16f)
                if (IconLayout.iconButton("notice-close-${notice.time}", ExpuiGeneralCloseSmallDark, "Dismiss")) {
                    Notifications.dismiss(notice)
                }
                ImGui.sameLine()
                val icon = icon(notice.kind)
                ImGui.text(IconLayout.spacesFor(IconLayout.gutter()) + notice.title)
                IconLayout.drawCentered(icon, ImGui.getItemRectMin(), ImGui.getItemRectMax())
                ImGui.textDisabled(notice.content)
                ImGui.popTextWrapPos()
                if (ImGui.isWindowHovered(cn.enaium.imgui.ImGuiHoveredFlags.CHILD_WINDOWS)) notice.held = true
                if (ImGui.isWindowHovered(cn.enaium.imgui.ImGuiHoveredFlags.CHILD_WINDOWS) &&
                    ImGui.isMouseClicked(cn.enaium.imgui.ImGuiMouseButton.LEFT) &&
                    !ImGui.isAnyItemHovered()
                ) {
                    core.showNotifications = true
                }
            }
            // Advance regardless of what Begin returned: a clipped balloon
            // still occupies its slot, or the next one would land on it.
            bottom -= ImGui.getWindowSize().y + 6f
            ImGui.end()
        }
    }

    /** The history: every notification, newest first. */
    fun drawWindow(core: AppCore) {
        val open = BooleanArray(1) { core.showNotifications }
        ImGui.setNextWindowSize(ImVec2(560f, 380f), cn.enaium.imgui.ImGuiCond.FIRST_USE_EVER)
        if (!ImGui.begin("Notifications", open)) {
            ImGui.end()
            return
        }
        if (!open[0]) core.showNotifications = false
        if (ImGui.button("Clear all")) Notifications.clear()
        ImGui.sameLine()
        ImGui.textDisabled("${Notifications.all().size} in history")
        ImGui.separator()
        ImGui.beginChild("##notice-list", ImVec2(0f, 0f), 0, ImGuiWindowFlags.NO_SCROLL_WITH_MOUSE)
        val now = Platform.currentTimeMillis()
        for (notice in Notifications.all()) {
            val icon = icon(notice.kind)
            ImGui.text(IconLayout.spacesFor(IconLayout.gutter()) + notice.title)
            IconLayout.drawCentered(icon, ImGui.getItemRectMin(), ImGui.getItemRectMax())
            ImGui.sameLine()
            ImGui.textDisabled("${notice.source} · ${ago(now - notice.time)}")
            ImGui.pushTextWrapPos(ImGui.getCursorPosX() + 520f)
            ImGui.textDisabled(notice.content)
            ImGui.popTextWrapPos()
            ImGui.separator()
        }
        ImGui.endChild()
        ImGui.end()
    }

    private fun ago(millis: Long): String {
        val seconds = millis / 1000
        return when {
            seconds < 60 -> "just now"
            seconds < 3600 -> "${seconds / 60} min ago"
            seconds < 86400 -> "${seconds / 3600} h ago"
            else -> "${seconds / 86400} d ago"
        }
    }
}
