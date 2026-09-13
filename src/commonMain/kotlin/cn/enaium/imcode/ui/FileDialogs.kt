package cn.enaium.imcode.ui

import cn.enaium.imgui.ImVec2
import cn.enaium.imgui.extensions.filedialog.FileDialog
import cn.enaium.imgui.extensions.filedialog.FileDialogConfig
import cn.enaium.imgui.extensions.filedialog.FileDialogInstance
import cn.enaium.imgui.extensions.filedialog.IgfdFlags

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
        val open = FileDialog.displayDialog(dialog, key, minSize = ImVec2(520f, 380f), maxSize = ImVec2(1600f, 1200f))
        isOpen = open
        if (!open) return

        if (FileDialog.isOk(dialog)) {
            val selection = FileDialog.getSelection(dialog)
            val paths = selection.map { it.second }.filter { it.isNotBlank() }
            finish(paths)
        } else if (!FileDialog.isKeyOpened(dialog, key)) {
            finish(emptyList())
        }
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