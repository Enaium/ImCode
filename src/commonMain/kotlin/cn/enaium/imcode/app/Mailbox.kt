package cn.enaium.imcode.app

import java.util.concurrent.atomic.AtomicInteger

/**
 * A thread-safe queue of tasks that must run on the UI (render) thread.
 * IO/LSP threads post into it; the frame loop drains it once per frame.
 */
class Mailbox {
    private val lock = Any()
    private val queue = ArrayDeque<() -> Unit>()
    private val pending = AtomicInteger()

    fun post(block: () -> Unit) {
        synchronized(lock) { queue.addLast(block) }
        pending.incrementAndGet()
    }

    fun drain() {
        while (true) {
            val task = synchronized(lock) { queue.removeFirstOrNull() } ?: break
            pending.decrementAndGet()
            try {
                task()
            } catch (t: Throwable) {
                // a failing mailbox task must never kill the frame loop
                OutputLog.append("App", LogLevel.ERROR, "mailbox task failed: " + t.stackTraceToString())
            }
        }
    }

    fun size(): Int = pending.get()
}