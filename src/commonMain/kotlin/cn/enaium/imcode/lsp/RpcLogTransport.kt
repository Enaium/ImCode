package cn.enaium.imcode.lsp

import cn.enaium.imcode.app.LogLevel
import cn.enaium.imcode.app.OutputLog
import cn.enaium.lsp.jsonrpc.MessageTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Wraps a [MessageTransport] and mirrors every framed JSON-RPC message into
 * the "LSP" output tab while [enabled] returns true. Lines are structured the
 * way lsp4ij's LSP console shows them: direction arrow, method name, request
 * id and round-trip duration, plus a compact payload summary:
 *
 *   -> textDocument/completion req#2 {position=12:4}
 *   <- textDocument/completion req#2 186ms [41 items]
 */
class RpcLogTransport(
    private val delegate: MessageTransport,
    private val serverName: String,
    private val enabled: () -> Boolean,
) : MessageTransport {

    private val json = Json { ignoreUnknownKeys = true }
    private val pending = HashMap<String, Pending>()

    private class Pending(val method: String, val startNanos: Long, val summary: String)

    /** frames whose payload is the entire file content: never log the body
     *  (it would flood the console with megabytes per keystroke) */
    private val contentMethods = setOf("textDocument/didOpen", "textDocument/didChange", "textDocument/didSave")

    override fun send(message: String) {
        val line = render(message, outgoing = true)
        if (line != null && enabled()) OutputLog.append(serverName, LogLevel.LSP, withPayload(line, message))
        delegate.send(message)
    }

    override fun receive(): String? {
        val message = delegate.receive()
        if (message != null) {
            val line = render(message, outgoing = false)
            if (line != null && enabled()) OutputLog.append(serverName, LogLevel.LSP, withPayload(line, message))
        }
        return message
    }

    /**
     * The frame and its payload as ONE entry.
     *
     * They used to be two entries — summary, then an indented payload — but
     * the console folds the payload under the summary, and the server's own
     * stderr arrives asynchronously: it kept landing between the two, so the
     * payload was no longer adjacent and stopped folding. One entry cannot be
     * split by anything.
     */
    private fun withPayload(line: String, message: String): String {
        val detail = detailFor(message) ?: return line
        return line + "\n" + detail
    }

    /**
     * Appends the indented follow-up entry the console expands: the complete
     * raw JSON-RPC frame, so the structure is there rather than a one-line
     * digest.
     *
     * Content-bearing methods carry the whole file, so their entry is the
     * *shape* of the change plus its size — the payload itself would flood
     * the console with megabytes per keystroke.
     */
    /** The payload line(s) for [message], or null when there is nothing to add. */
    private fun detailFor(message: String): String? {
        val root = try {
            json.parseToJsonElement(message) as? JsonObject
        } catch (_: Throwable) {
            null
        }
        val method = (root?.get("method") as? JsonPrimitive)?.content
        return when {
            method in contentMethods -> {
                val summary = summarize(root?.get("params"))
                "$summary (payload elided: ${message.length} bytes)"
            }
            method != null || message.startsWith("[") || message.startsWith("{") -> message
            else -> null
        }
    }

    private fun render(message: String, outgoing: Boolean): String? {
        val root = try {
            json.parseToJsonElement(message) as? JsonObject
        } catch (_: Throwable) {
            null
        } ?: return if (outgoing) "[send] $message" else "[recv] $message"

        val method = (root["method"] as? JsonPrimitive)?.content
        val id = (root["id"] as? JsonPrimitive)?.content
        val stamp = cn.enaium.imcode.platform.Platform.timeOfDay()

        return when {
            // request or notification, in either direction
            method != null -> {
                // The collapsed line names the frame only — the payload is
                // the indented follow-up entry the console expands on click.
                val verb = if (id != null) {
                    if (outgoing) "Sending request" else "Received request"
                } else {
                    if (outgoing) "Sending notification" else "Received notification"
                }
                val what = if (id != null) "$method - ($id)" else method
                "[$stamp] $verb '$what'."
            }
            // response to a request we sent
            id != null -> {
                val req = pending.remove(id)
                val methodPart = req?.method ?: "response"
                val durMs = req?.let { (System.nanoTime() - it.startNanos) / 1_000_000 } ?: -1L
                val durPart = if (durMs >= 0) " in ${durMs}ms" else ""
                val failed = root["error"] != null
                // A failed request says so without being expanded; a result
                // is the follow-up entry.
                val body = if (failed) {
                    val code = (root["error"] as? JsonObject)?.get("code")?.toString() ?: "?"
                    " ERROR code=$code"
                } else {
                    ""
                }
                "[$stamp] Received response '$methodPart - ($id)'$durPart.$body"
            }
            else -> null
        }
    }

    /** Recursive one-line payload rendering: nested objects keep their VALUES
     *  (line/character, triggerKind, uri...) instead of collapsing to
     *  "{N fields}" — bounded by [budget] chars and [depth] levels so one
     *  line stays readable. Array element counts shown as "[N item-type]". */
    private fun summarize(element: JsonElement?): String =
        if (element == null) "" else render(element, budget = 240, depth = 3)

    private fun render(element: JsonElement, budget: Int, depth: Int): String = when (element) {
        is JsonNull -> "null"
        is JsonPrimitive -> {
            val s = element.content
            if (s.length > 64) s.take(62) + "…" else s
        }
        is JsonArray -> {
            if (element.isEmpty()) {
                "[]"
            } else {
                val first = render(element.first(), budget, depth - 1)
                if (element.size == 1) "[$first]" else "[$first +${element.size - 1} more]"
            }
        }
        is JsonObject -> {
            val sb = StringBuilder("{")
            var remaining = budget
            for ((k, v) in element) {
                if (sb.length > 1) sb.append(' ')
                val piece = "$k=" + render(v, (remaining).coerceAtLeast(8), depth - 1)
                if (sb.length + piece.length > budget) {
                    sb.append("...")
                    break
                }
                sb.append(piece)
                remaining -= piece.length
            }
            sb.append('}')
            sb.toString()
        }
    }

}