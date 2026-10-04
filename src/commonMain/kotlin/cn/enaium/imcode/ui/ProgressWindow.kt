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
        // A size of its own, not one fitted to the content: the bars fill the
        // window, and an auto-resizing window then grows to fit them, which
        // grows the bars again — the width ran away. The user can still
        // resize it; FIRST_USE_EVER only sets where it starts.
        ImGui.setNextWindowSize(ImVec2(420f, 200f), ImGuiCond.FIRST_USE_EVER)
        // Above the status bar, right-aligned over the progress indicator —
        // where IntelliJ puts its background tasks popup. Pivot (1,1) anchors
        // the window's bottom-right corner there.
        val display = ImGui.getIO().displaySize
        ImGui.setNextWindowPos(
            ImVec2(display.x - MARGIN, display.y - StatusBar.height() - MARGIN),
            ImGuiCond.FIRST_USE_EVER,
            ImVec2(1f, 1f),
        )
        val flags = ImGuiWindowFlags.NO_DOCKING
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
            // Full width of the window's content region, which is now stable.
            val fraction = task.fraction
            ImGui.progressBar(fraction ?: -1f, ImVec2(-1f, 0f), fraction?.let { "${(it * 100).toInt()}%" } ?: "")
        }
        ImGui.end()
    }
}
