package cn.enaium.imcode.app

/** Log severity levels (UI colors are assigned in the output panel). */
object LogLevel {
    const val INFO = 0
    const val WARN = 1
    const val ERROR = 2
    const val LSP = 3
    /** Language server process stdout/stderr lines (shown in the LSP tab). */
    const val SERVER = 4
}

/**
 * App-wide output log. Thread-safe: LSP processes, coroutines and the render
 * loop all append here. Rendered in every workspace's bottom panel.
 */
object OutputLog {
    data class Entry(val tag: String, val level: Int, val text: String)

    private const val MAX_ENTRIES = 4000
    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    @Volatile
    var revision = 0
        private set

    fun append(tag: String, level: Int, text: String) {
        val safe = text.replace('\r', ' ')
        synchronized(lock) {
            if (entries.size >= MAX_ENTRIES) entries.removeFirst()
            entries.addLast(Entry(tag, level, safe))
        }
        revision++
    }

    fun info(tag: String, text: String) = append(tag, LogLevel.INFO, text)
    fun warn(tag: String, text: String) = append(tag, LogLevel.WARN, text)
    fun error(tag: String, text: String) = append(tag, LogLevel.ERROR, text)

    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    fun clear() {
        synchronized(lock) { entries.clear() }
        revision++
    }
}