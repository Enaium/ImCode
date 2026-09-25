package cn.enaium.imcode.ui

import cn.enaium.imcode.platform.Platform

/**
 * User-facing notifications: the balloons that appear bottom-right and the
 * history window behind them, IntelliJ style.
 *
 * [cn.enaium.imcode.app.OutputLog] stays the developer log; the app raises a
 * notification for anything the user must not miss (see the hook it installs
 * on the log), so the two never disagree about what happened.
 */
internal object Notifications {

    enum class Kind { INFO, WARNING, ERROR }

    class Notice(
        val kind: Kind,
        val title: String,
        val content: String,
        val source: String,
        val time: Long,
    ) {
        /** Seconds the balloon stays up; errors wait for the user. */
        var remaining: Float = when (kind) {
            Kind.ERROR -> Float.MAX_VALUE
            Kind.WARNING -> 10f
            Kind.INFO -> 6f
        }

        /** True while the pointer is over the balloon: it stops ageing. */
        var held: Boolean = false

        var dismissed: Boolean = false
    }

    private const val MAX = 200

    private val lock = Any()
    private val notices = ArrayList<Notice>()

    /**
     * Notifications newest first.
     *
     * Errors reach this from worker threads (language servers, file reads), so
     * every access is guarded the way [cn.enaium.imcode.app.OutputLog] guards
     * its own list.
     */
    fun all(): List<Notice> = synchronized(lock) { notices.toList() }

    /** Balloons still on screen. */
    fun pending(): List<Notice> = synchronized(lock) { notices.filter { !it.dismissed } }

    fun info(title: String, content: String, source: String = "ImCode") =
        add(Kind.INFO, title, content, source)

    fun warning(title: String, content: String, source: String = "ImCode") =
        add(Kind.WARNING, title, content, source)

    fun error(title: String, content: String, source: String = "ImCode") =
        add(Kind.ERROR, title, content, source)

    fun dismiss(notice: Notice) {
        notice.dismissed = true
    }

    fun clear() {
        synchronized(lock) { notices.clear() }
    }

    fun add(kind: Kind, title: String, content: String, source: String = "ImCode"): Notice =
        synchronized(lock) { addLocked(kind, title, content, source) }

    private fun addLocked(kind: Kind, title: String, content: String, source: String): Notice {
        // The same failure repeats (a server that fails on every keystroke);
        // keep one entry per identical message instead of a wall of them.
        val existing = notices.firstOrNull {
            it.kind == kind && it.title == title && it.content == content
        }
        if (existing != null) {
            existing.remaining = existing.remaining.coerceAtLeast(6f)
            existing.dismissed = false
            return existing
        }
        val notice = Notice(kind, title, content, source, Platform.currentTimeMillis())
        notices.add(0, notice)
        while (notices.size > MAX) notices.removeAt(notices.size - 1)
        return notice
    }

    /** Ages the balloons; called once per frame by the window drawing them. */
    fun tick(delta: Float) {
        for (notice in all()) {
            if (notice.dismissed || notice.held) continue
            if (notice.remaining == Float.MAX_VALUE) continue
            notice.remaining -= delta
            if (notice.remaining <= 0f) notice.dismissed = true
        }
    }
}
