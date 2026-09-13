package cn.enaium.imcode.util

import cn.enaium.imcode.platform.IoFile
import cn.enaium.imcode.platform.ioFile

/** Path <-> LSP `file://` URI conversions (percent-encode/decode, UTF-8-safe). */
object Uri {
    private fun encode(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val ub = b.toInt() and 0xFF
            val safe = ub in 'a'.code..'z'.code || ub in 'A'.code..'Z'.code ||
                ub in '0'.code..'9'.code || ub in "-._~/".map { it.code }
            if (safe) sb.append(ub.toChar()) else sb.append('%').append("%02X".format(ub))
        }
        return sb.toString()
    }

    private fun decode(s: String): String {
        val bytes = ArrayList<Byte>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length + 1 && i + 2 < s.length + 1) {
                val hex = s.substring(i + 1, minOf(i + 3, s.length))
                if (hex.length == 2 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                    bytes.add(hex.toInt(16).toByte())
                    i += 3
                    continue
                }
            }
            val asBytes = c.toString().toByteArray(Charsets.UTF_8)
            bytes.addAll(asBytes.toList())
            i++
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    fun pathToUri(path: String): String {
        val abs = ioFile(path).absolutePath
        val encoded = encode(abs)
        return if (encoded.startsWith("file:")) "file://$encoded" else "file://$encoded"
    }

    /** URI -> local path, or null when the URI is not a plain file URI. */
    fun uriToPath(uri: String): String? {
        if (!uri.startsWith("file:")) return null
        // "file:///path" -> "///path": drop the empty authority ("//") and
        // keep the path's leading slash; "file://host/path" keeps "host/...".
        val withoutAuthority = uri.removePrefix("file:").removePrefix("//")
        return decode(withoutAuthority)
    }

    fun fileName(uri: String): String = ioFile(uriToPath(uri) ?: uri).name
}