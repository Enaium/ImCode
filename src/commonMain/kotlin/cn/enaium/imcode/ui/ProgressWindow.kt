package cn.enaium.imcode.ui

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiCond
import cn.enaium.imgui.ImGuiWindowFlags
import cn.enaium.imgui.ImVec2
import cn.enaium.imcode.app.AppCore
import cn.enaium.xicons.imgui.icons.intellij.ExpuiGeneralCloseSmallDark

/**
 * Every running job, opened by clicking the progress bar in the status bar.
 *
 * A window rather than a popup: it stays while the user works, closes with its
 * title-bar button, and closes itself once nothing is running.
 */
internal object ProgressWindow {

    private const val MARGIN = 12f

    fun draw(core: AppCore) {
        // Only on request: the status bar's bar is the indicator, this window
        // is what clicking it opens.
        if (!core.showProgress) return
        val tasks = ProgressCenter.all()
        if (tasks.isEmpty()) {
            // Nothing left to show: the window disappears on its own.
            core.showProgress = false
            return
        }
        val openArr = BooleanArray(1) { core.showProgress }
        ImGui.setNextWindowSize(ImVec2(420f, 0f), ImGuiCond.FIRST_USE_EVER)
        // Above the status bar, right-aligned over the progress indicator —
        // where IntelliJ puts its background tasks popup. Pivot (1,1) anchors
        // the window's bottom-right corner there, so its content-driven height
        // does not matter; FIRST_USE_EVER leaves it movable afterwards.
        val display = ImGui.getIO().displaySize
        ImGui.setNextWindowPos(
            ImVec2(display.x - MARGIN, display.y - StatusBar.height() - MARGIN),
            ImGuiCond.FIRST_USE_EVER,
            ImVec2(1f, 1f),
        )
        val flags = ImGuiWindowFlags.ALWAYS_AUTO_RESIZE or ImGuiWindowFlags.NO_DOCKING
        if (!ImGui.begin("Background Tasks", openArr, flags)) {
            ImGui.end()
            return
        }
        if (!openArr[0]) core.showProgress = false
        for (task in tasks) {
            ImGui.text(task.title)
            task.message?.let {
                ImGui.sameLine()
                ImGui.textDisabled(it)
            }
            val cancel = task.cancel
            if (cancel != null) {
                ImGui.sameLine()
                if (IconLayout.iconButton("task-stop-${task.id}", ExpuiGeneralCloseSmallDark, "Stop")) {
                    cancel.invoke()
                    ProgressCenter.finish(task.id)
                }
            }
            val fraction = task.fraction
            ImGui.progressBar(fraction ?: -1f, ImVec2(-1f, 0f), fraction?.let { "${(it * 100).toInt()}%" } ?: "")
        }
        ImGui.end()
    }
}
