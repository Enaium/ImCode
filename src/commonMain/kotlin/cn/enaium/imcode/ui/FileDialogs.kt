package cn.enaium.imcode.ui

import cn.enaium.imgui.ImVec2
import cn.enaium.imgui.extensions.filedialog.FileDialog
import cn.enaium.imgui.extensions.filedialog.FileDialogConfig
import cn.enaium.imgui.extensions.filedialog.FileDialogInstance
import cn.enaium.imgui.extensions.filedialog.IgfdFlags
import cn.enaium.imcode.app.OutputLog
import cn.enaium.imcode.platform.ioFile

/**
 * One-shot file dialogs over the ImGuiFileDialog binding. Call [render] every
 * frame; results are delivered through the callback once.
 */
class FileDialogs {
    private val dialog: FileDialogInstance = FileDialog.create()

    private enum class PendingKind { OPEN_FILE, OPEN_FOLDER }

    private var pending: PendingKind? = null
    private var callback: ((List<String>) -> Unit)? = null

    var isOpen: Boolean = false
        private set

    fun pickFiles(title: String, filters: String, onResult: (List<String>) -> Unit) {
        FileDialog.openDialog(
            dialog, "imcode-open-file", title, filters,
            FileDialogConfig(flags = IgfdFlags.DEFAULT or IgfdFlags.CASE_INSENSITIVE_EXTENTION_FILTERING),
        )
        pending = PendingKind.OPEN_FILE
        callback = onResult
    }

    fun pickDirectory(title: String, onResult: (List<String>) -> Unit) {
        FileDialog.openDialog(
            dialog, "imcode-open-dir", title, null,
            FileDialogConfig(flags = IgfdFlags.DEFAULT),
        )
        pending = PendingKind.OPEN_FOLDER
        callback = onResult
    }

    /** Draws the dialog; call on the render thread every frame. */
    fun render() {
        val key = when (pending) {
            PendingKind.OPEN_FILE -> "imcode-open-file"
            PendingKind.OPEN_FOLDER -> "imcode-open-dir"
            null -> return
        }
        // `displayDialog` returns true only on the frame a result is ready (OK
        // or Cancel pressed), not "while the dialog is open". ImGuiFileDialog
        // also does not close itself: it keeps the dialog in its state until
        // the host calls closeDialog, so a result that is not consumed here
        // leaves the window on screen forever — which is exactly what Cancel
        // used to do.
        val hasResult = FileDialog.displayDialog(
            dialog, key,
            minSize = ImVec2(520f, 380f),
            maxSize = ImVec2(1600f, 1200f),
        )
        isOpen = FileDialog.isKeyOpened(dialog, key)
        if (!hasResult) return
        // OK delivers a selection; Cancel leaves IsOk false and delivers none,
        // which the callback receives as an empty list.
        val ok = FileDialog.isOk(dialog)
        val paths = if (ok) {
            val selection = FileDialog.getSelection(dialog).map { it.second }.filter { it.isNotBlank() }
            if (pending == PendingKind.OPEN_FOLDER) {
                // A directory dialog reports the selection as the file name
                // joined to the current path, so a folder that is entered
                // *after* being selected comes back as `.../src/src`. Only a
                // path that really is a directory can be the chosen folder;
                // otherwise the folder the dialog is in is the answer.
                val picked = selection.filter { ioFile(it).isDirectory }
                if (picked.isNotEmpty()) {
                    picked
                } else {
                    listOfNotNull(FileDialog.getCurrentPath(dialog).takeIf { it.isNotBlank() })
                }
            } else {
                selection
            }
        } else {
            emptyList()
        }
        OutputLog.info("App", "dialog $key finished: ok=$ok paths=$paths")
        FileDialog.closeDialog(dialog, key)
        finish(paths)
    }

    private fun finish(paths: List<String>) {
        pending = null
        isOpen = false
        val cb = callback
        callback = null
        cb?.invoke(paths)
    }

    fun close() {
        FileDialog.destroy(dialog)
    }
}