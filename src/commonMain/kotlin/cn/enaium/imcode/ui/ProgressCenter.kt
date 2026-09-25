package cn.enaium.imcode.ui

import cn.enaium.imcode.lsp.LspProgress

/**
 * Everything the status bar reports as "work in progress".
 *
 * One entry per running job. Language servers report work-done progress with
 * a title, a message and an optional percentage; a job without a percentage
 * is indeterminate and is drawn as an animated bar (IntelliJ's "unknown
 * progress"). Entries a job can be asked to stop carry [cancel], which is
 * what the close button in the popup calls.
 */
internal object ProgressCenter {

    /** One running job. A null [fraction] means "indeterminate". */
    class Task(
        val id: String,
        val title: String,
        var message: String? = null,
        var fraction: Float? = null,
        /** Stops the job; null when it cannot be stopped, and the popup then
         *  offers no close button rather than a button that does nothing. */
        val cancel: (() -> Unit)? = null,
    )

    private val tasks = LinkedHashMap<String, Task>()

    /** Snapshot for the UI; the render thread only. */
    fun all(): List<Task> = tasks.values.toList()

    fun start(task: Task) {
        tasks[task.id] = task
    }

    /**
     * The most recently started job: what the status bar shows while several
     * run, with the rest one click away in the window.
     */
    fun latest(): Task? = tasks.values.lastOrNull()

    fun finish(id: String) {
        tasks.remove(id)
    }

    fun clear() {
        tasks.clear()
    }

    /**
     * Mirrors the language servers' work-done progress, keyed by title so a
     * job that reports twice updates one entry.
     *
     * Cancellation is not offered: the protocol has no request for it, and a
     * close button that silently does nothing is worse than none.
     */
    fun syncLsp(reported: List<LspProgress>) {
        val seen = HashSet<String>()
        for (progress in reported) {
            val id = "lsp:" + progress.title
            seen.add(id)
            val fraction = progress.percentage?.let { it / 100f }
            val existing = tasks[id]
            if (existing == null) {
                tasks[id] = Task(id, progress.title, progress.message, fraction)
            } else {
                existing.message = progress.message
                existing.fraction = fraction
            }
        }
        tasks.keys.filter { it.startsWith("lsp:") && it !in seen }.forEach { tasks.remove(it) }
    }
}
