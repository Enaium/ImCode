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
        if (line != null && enabled()) {
            OutputLog.append(serverName, LogLevel.LSP, line)
            logFull(message)
        }
        delegate.send(message)
    }

    override fun receive(): String? {
        val message = delegate.receive()
        if (message != null) {
            val line = render(message, outgoing = false)
            if (line != null && enabled()) {
                OutputLog.append(serverName, LogLevel.LSP, line)
                logFull(message)
            }
        }
        return message
    }

    /** Appends the COMPLETE raw JSON-RPC frame (indented) so the console shows
     *  the full structure, not just the summary. Content-bearing methods log
     *  the byte size instead of the payload. */
    private fun logFull(message: String) {
        val method = try {
            (json.parseToJsonElement(message) as? JsonObject)?.get("method") as? JsonPrimitive
        } catch (_: Throwable) {
            null
        }?.content
        if (method in contentMethods) {
            OutputLog.append(serverName, LogLevel.LSP, "    (full payload elided: ${message.length} bytes)")
        } else if (method != null || message.startsWith("[") || message.startsWith("{")) {
            OutputLog.append(serverName, LogLevel.LSP, "    " + message.replace("\n", "\n    "))
        }
    }

    private fun render(message: String, outgoing: Boolean): String? {
        val root = try {
            json.parseToJsonElement(message) as? JsonObject
        } catch (_: Throwable) {
            null
        } ?: return if (outgoing) "[send] $message" else "[recv] $message"

        val method = (root["method"] as? JsonPrimitive)?.content
        val id = when (val v = root["id"]) {
            is JsonPrimitive -> v.content
            is JsonNull -> null
            null -> null
            else -> null
        }

        return if (method != null) {
            // request or notification (client -> server, or server -> client)
            val idPart = id?.let { " req#$it" } ?: ""
            val summary = summarize(root["params"])
            if (outgoing) {
                if (id != null) pending[id] = Pending(method, System.nanoTime(), summary)
                "[send] $method$idPart${if (summary.isEmpty()) "" else " $summary"}"
            } else {
                "${
                if (outgoing) "[send]" else "[recv]"
            } $method$idPart${if (summary.isEmpty()) "" else " $summary"}"
            }
        } else if (id != null) {
            // response to a request we sent
            val req = pending.remove(id)
            val err = root["error"] != null
            val durMs = req?.let { (System.nanoTime() - it.startNanos) / 1_000_000 } ?: -1L
            val methodPart = req?.method ?: "response"
            val durPart = if (durMs >= 0) "${durMs}ms" else "?"
            val body = if (err) {
                val code = (root["error"] as? JsonObject)?.get("code")?.toString() ?: "?"
                "ERROR code=$code"
            } else {
                val summary = summarize(root["result"])
                if (summary.isEmpty()) "(no result)" else summary
            }
            "[recv] $methodPart req#$id $durPart $body"
        } else {
            null
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