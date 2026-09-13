package cn.enaium.imcode.search

import cn.enaium.imcode.platform.IoFile
import cn.enaium.imcode.platform.ioFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One global-search hit. */
data class SearchHit(
    val path: String,
    val line: Int = 0,        // 0-based
    val lineText: String = "",
)

/**
 * Global search over a workspace directory: file-name search and content
 * search, both on a background dispatcher with result limits. Safe to call
 * from the render thread; results are delivered through [onResults] (which
 * SHOULD marshal to the render thread).
 */
class SearchService {
    /** Set by the UI; invoked from the search dispatcher (NOT the render thread). */
    @Volatile
    var onResults: ((searchId: Int, hits: List<SearchHit>) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var currentJob: Job? = null
    private var nextId = 0

    /** Cancels the running search and starts a new one. */
    fun search(root: String, mode: Mode, query: String, maxResults: Int = 500) {
        currentJob?.cancel()
        if (query.isBlank()) {
            onResults?.invoke(nextId++, emptyList())
            return
        }
        val id = nextId++
        currentJob = scope.launch {
            val hits = ArrayList<SearchHit>()
            val stack = ArrayDeque<IoFile>()
            stack.addLast(ioFile(root))
            val expected = if (mode == Mode.FILE_NAME) query.lowercase() else ""
            val needle = if (mode == Mode.CONTENT) query.lowercase() else ""
            var scanned = 0
            outer@ while (stack.isNotEmpty() && isActive && hits.size < maxResults) {
                val dir = stack.removeLast()
                val children = dir.listFiles() ?: continue
                for (child in children) {
                    if (!isActive || hits.size >= maxResults) break@outer
                    val name = child.name
                    if (name.startsWith(".") && name != ".git") continue
                    scanned++
                    if (scanned > 60_000) break@outer
                    if (child.isDirectory) {
                        stack.addLast(child)
                    } else {
                        when (mode) {
                            Mode.FILE_NAME -> {
                                if (name.lowercase().contains(expected)) {
                                    hits.add(SearchHit(child.absolutePath))
                                }
                            }
                            Mode.CONTENT -> {
                                if (child.length() > 2_000_000) continue
                                try {
                                    val content = child.readText()
                                    val lower = content.lowercase()
                                    var idx = lower.indexOf(needle)
                                    var count = 0
                                    while (idx >= 0 && count < 20) {
                                        val line = content.substring(0, idx).count { it == '\n' }
                                        val lineStart = content.lastIndexOf('\n', idx.coerceAtLeast(0)) + 1
                                        val lineEnd = content.indexOf('\n', idx).let { if (it < 0) content.length else it }
                                        val lineText = content.substring(lineStart, lineEnd).trim().take(160)
                                        hits.add(SearchHit(child.absolutePath, line, lineText))
                                        count++
                                        idx = lower.indexOf(needle, idx + 1)
                                    }
                                } catch (_: Throwable) {
                                }
                            }
                        }
                    }
                }
            }
            onResults?.invoke(id, hits)
        }
    }

    fun cancel() {
        currentJob?.cancel()
        currentJob = null
    }

    fun shutdown() {
        currentJob?.cancel()
    }

    enum class Mode { FILE_NAME, CONTENT }
}