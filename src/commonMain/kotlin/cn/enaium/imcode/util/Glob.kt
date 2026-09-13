package cn.enaium.imcode.util

/**
 * Minimal file-name glob used for LSP server pattern matching
 * (`*.kt`, `*.kts`, `*.gradle.kts`, `*Test*.java`, ...).
 *
 * `*` matches any run of characters, `?` matches exactly one. A pattern
 * without a path separator matches against the file name only; a pattern with
 * a path separator matches against the path relative to the workspace root
 * (when such a relative path is available).
 */
object Glob {
    private fun toRegex(pattern: String): Regex {
        val sb = StringBuilder("^")
        for (c in pattern) {
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                '.', '(', ')', '+', '|', '^', '$', '[', ']', '{', '}', '\\' -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
        }
        sb.append('$')
        return Regex(sb.toString())
    }

    private val patterns = HashMap<String, Regex>()

    fun matches(pattern: String, fileName: String, relativePath: String? = null): Boolean {
        val regex = patterns.getOrPut(pattern) { toRegex(pattern) }
        if (regex.matches(fileName)) return true
        if (pattern.contains('/') && relativePath != null) {
            return regex.matches(relativePath)
        }
        return false
    }

    /** True when any pattern in [patterns] matches the file. */
    fun matchesAny(patterns: List<String>, fileName: String, relativePath: String? = null): Boolean =
        patterns.any { matches(it, fileName, relativePath) }
}