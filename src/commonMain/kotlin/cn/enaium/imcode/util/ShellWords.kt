package cn.enaium.imcode.util

/**
 * Splits a shell-style command line into argv tokens. Quoted segments
 * (`"..."` / `'...'`) stay together; backslash escapes the next character
 * inside double quotes (and outside when followed by a quote or backslash).
 * Used to let users configure LSP launch commands as a plain string, e.g.
 * `kotlin-lsp --log-level debug`.
 */
object ShellWords {
    fun split(line: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var inSingle = false
        var inDouble = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inSingle -> {
                    if (c == '\'') inSingle = false else cur.append(c)
                }
                inDouble -> {
                    when {
                        c == '"' -> inDouble = false
                        c == '\\' && i + 1 < line.length -> {
                            cur.append(line[i + 1]); i++
                        }
                        else -> cur.append(c)
                    }
                }
                c == '\'' -> { inSingle = true }
                c == '"' -> { inDouble = true }
                c.isWhitespace() -> {
                    if (cur.isNotEmpty()) { out.add(cur.toString()); cur.clear() }
                }
                c == '\\' && i + 1 < line.length && (line[i + 1] == '"' || line[i + 1] == '\'' || line[i + 1] == '\\') -> {
                    cur.append(line[i + 1]); i++
                }
                else -> cur.append(c)
            }
            i++
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    /**
     * Reconstructs a shell-style command line from argv tokens — the inverse
     * of [split]. Tokens that need quoting (contain whitespace, quotes or a
     * backslash) are wrapped in double quotes with `\"` / `\\` escapes, so
     * a token like `--log-level=debug info` round-trips through
     * split(join(tokens)) == tokens. Plain tokens join with single spaces.
     */
    fun join(tokens: List<String>): String {
        return tokens.joinToString(" ") { token ->
                        if (token.isEmpty() || token.any { it.isWhitespace() || it == '"' || it == '\\' }) {
                val escaped = token
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                "\"$escaped\""
            } else {
                token
            }
        }
    }
}