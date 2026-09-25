package cn.enaium.imcode.lsp

import cn.enaium.lsp.edit.EditOp
import cn.enaium.imcode.app.Mailbox
import cn.enaium.imcode.app.OutputLog
import cn.enaium.imcode.config.Config
import cn.enaium.imcode.config.LspServer
import cn.enaium.imcode.editor.Document
import cn.enaium.imcode.platform.ioFile
import cn.enaium.imcode.platform.isUnder
import cn.enaium.imcode.util.Uri
import cn.enaium.lsp.model.Diagnostic
import cn.enaium.lsp.model.Location
import cn.enaium.lsp.model.Position
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the lifecycle of every configured language server, lsp4ij-style:
 *
 * - a server starts when the first open document matches one of its
 *   patterns (and stops when the *last* matching document closes),
 * - stop is debounced (1.5 s) so closing/reopening a tab does not thrash
 *   the process,
 * - didOpen/didChange/didClose/didSave are routed to the matching servers.
 *
 * All public methods run on the render thread; the heavy lifting happens on
 * the client's own dispatcher.
 */
class LspManager(
    private val config: () -> Config,
    private val mailbox: Mailbox,
    private val rpcLoggingEnabled: () -> Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clients = LinkedHashMap<String, LspServerClient>()
    private val stopJobs = HashMap<String, Job>()

    /** Called on the render thread when a doc opens/changes/closes. */
    private var currentDocs: List<Document> = emptyList()

    var onDiagnostics: ((Document, List<Diagnostic>) -> Unit)? = null
    var onApplyEdit: ((Document?, Any) -> Unit)? = null
    var onServerReady: (() -> Unit)? = null

    // ==================== lifecycle ====================

    fun syncLifecycle(docs: List<Document>) {
        currentDocs = docs
        val servers = config().lspServers
        val known = HashSet<String>()
        servers.forEachIndexed { index, server ->
            val key = keyOf(index, server)
            known.add(key)
            val matched = docs.filter { serverMatches(server, it) }
            val client = clients[key]
            when {
                client == null && matched.isNotEmpty() -> startServer(key, server, matched)
                client != null && matched.isEmpty() -> scheduleStop(key)
                client != null && matched.isNotEmpty() -> {
                    stopJobs.remove(key)?.cancel()
                    if (client.isRunning) {
                        // A changed launch command (or env) must take effect:
                        // stop the old process and start a fresh one with the
                        // new configuration. The key (index::name) is
                        // unchanged by a command edit, so without this check
                        // the old server keeps running forever.
                        if (client.config.command != server.command ||
                            client.config.env != server.env ||
                            client.config.patterns != server.patterns ||
                            client.config.languageId != server.languageId
                        ) {
                            stopClient(key)
                            startServer(key, server, matched)
                        } else {
                            for (doc in matched) client.didOpen(doc)
                        }
                    }
                }
            }
        }
        // servers removed from the config get stopped
        clients.keys.filter { it !in known }.forEach { key ->
            stopJobs.remove(key)?.cancel()
            stopClient(key)
        }
    }

    private fun keyOf(index: Int, server: LspServer): String = "$index::${server.name}"

    private fun serverMatches(server: LspServer, doc: Document): Boolean {
        val rel = doc.workspaceDir?.let { ws ->
            runCatching { ioFile(ws).relativize(ioFile(doc.path)) }.getOrNull()
        }
        return cn.enaium.imcode.util.Glob.matchesAny(server.patterns, ioFile(doc.path).name, rel)
    }

    private fun startServer(key: String, server: LspServer, matched: List<Document>) {
        val root = matched.firstNotNullOfOrNull { it.workspaceDir } ?: ioFile(matched.first().path).parent?.absolutePath ?: cn.enaium.imcode.platform.Platform.userHome
        val client = LspServerClient(server, rpcLoggingEnabled, mailbox)
        client.onDiagnostics = { path, _, diagnostics ->
            val doc = currentDocs.firstOrNull { it.path == path }
            if (doc != null) onDiagnostics?.invoke(doc, diagnostics)
        }
        client.onApplyEdit = { edit -> onApplyEdit?.invoke(null, edit) }
        client.onServerReady = { onServerReady?.invoke() }
        clients[key] = client
        client.start(root, matched)
    }

    private fun scheduleStop(key: String) {
        if (stopJobs.containsKey(key)) return
        val job = scope.launch {
            delay(1500)
            stopJobs.remove(key)
            stopClient(key)
        }
        stopJobs[key] = job
    }

    private fun stopClient(key: String) {
        val client = clients.remove(key) ?: return
        client.stop()
    }

    // ==================== document events (render thread) ====================

    fun documentChanged(doc: Document, ops: List<EditOp>) {
        for (client in clients.values) {
            if (client.matches(doc.path)) client.didChangeIncremental(doc, ops)
        }
    }

    fun documentSaved(doc: Document, text: String) {
        for (client in clients.values) {
            if (client.matches(doc.path)) client.didSave(doc, text)
        }
    }

    fun documentClosed(doc: Document) {
        for (client in clients.values) {
            if (client.matches(doc.path)) client.didClose(doc.path, doc.uri)
        }
    }

    // ==================== feature requests (render thread) ====================

    /** Completion for [doc] at [pos]; result arrives on the render thread. */
    fun requestCompletion(doc: Document, pos: Position, triggerChar: String?, onResult: (List<cn.enaium.lsp.model.CompletionItem>) -> Unit) {
        val client = matchingClient(doc)
        if (client != null) client.requestCompletion(doc, pos, triggerChar, onResult)
        else onResult(emptyList())
    }

    fun requestHover(doc: Document, pos: Position, onResult: (String?) -> Unit) {
        val client = matchingClient(doc)
        if (client != null) client.requestHover(doc, pos, onResult) else onResult(null)
    }

    fun requestDefinition(doc: Document, pos: Position, onResult: (Location?) -> Unit) {
        val client = matchingClient(doc)
        if (client != null) client.requestDefinition(doc, pos, onResult) else onResult(null)
    }

    /** Test hook: register a running client under [key] so feature requests
     *  route to it without launching a server (integration tests reuse the
     *  class-shared kotlin-lsp instance this way). */
    internal fun registerClientForTest(key: String, client: LspServerClient) {
        clients[key] = client
        for ((k, v) in clients) if (v === client && k != key) clients.remove(k)
    }

    /** True when any configured server pattern matches [doc] (regardless of whether it is running). */
    fun hasMatchingServer(doc: Document): Boolean {
        val servers = config().lspServers
        return servers.any { serverMatches(it, doc) }
    }

    fun matchingClient(doc: Document): LspServerClient? {
        val servers = config().lspServers
        return servers.indices.firstNotNullOfOrNull { index ->
            val server = servers[index]
            if (serverMatches(server, doc)) {
                clients[keyOf(index, server)]?.takeIf { it.isRunning }
            } else null
        }
    }

    /** Trigger characters for the server that owns [doc] (empty when not running). */
    fun triggerCharactersFor(doc: Document): Set<String> =
        matchingClient(doc)?.triggerCharacters ?: emptySet()

    /** Work-done progress from every running server (render thread). */
    fun activeProgress(): List<LspProgress> = clients.values.flatMap { it.activeProgress() }

    fun statusLine(): String {
        if (clients.isEmpty()) return "no LSP servers running"
        return clients.values.joinToString(" | ") {
            "${it.config.name}: ${it.statusText}"
        }
    }

    fun serversRunning(): List<LspServerClient> = clients.values.toList()


    /** workspace/executeCommand routed to the server that owns [doc]. */
    fun requestExecuteCommand(
        doc: Document,
        command: String,
        arguments: List<kotlinx.serialization.json.JsonElement>?,
        onResult: (kotlinx.serialization.json.JsonElement?) -> Unit,
    ) {
        val client = matchingClient(doc)
        if (client != null) client.requestExecuteCommand(command, arguments, onResult) else onResult(null)
    }

    /** Full-document semantic tokens from the server that owns [doc]. */
    fun requestSemanticTokensFull(doc: Document, onResult: (cn.enaium.lsp.model.SemanticTokens?) -> Unit) {
        val client = matchingClient(doc)
        if (client != null) client.requestSemanticTokensFull(doc, onResult) else onResult(null)
    }

    // ==================== structure / lenses / symbols / refs ====================

    /** Document symbol tree for [doc]; result on the render thread. */
    fun requestDocumentSymbols(doc: Document, onResult: (List<cn.enaium.lsp.model.DocumentSymbol>) -> Unit) {
        val client = matchingClient(doc)
        if (client != null) client.requestDocumentSymbols(doc, onResult) else onResult(emptyList())
    }

    /** Code actions for [doc] over [range]; result on the render thread. */
    fun requestCodeActions(
        doc: Document,
        range: cn.enaium.lsp.model.Range,
        diagnostics: List<cn.enaium.lsp.model.Diagnostic>,
        onResult: (List<cn.enaium.lsp.model.CodeAction>) -> Unit,
    ) {
        val client = matchingClient(doc)
        if (client != null) {
            client.requestCodeActions(doc, range, diagnostics, onResult)
        } else {
            onResult(emptyList())
        }
    }

    /** The running server that owns [doc], if any. */
    fun runningClientFor(doc: Document): LspServerClient? =
        clients.values.firstOrNull { it.isRunning && it.matches(doc.path) }

    /** Resolves a lazily-filled code action. */
    fun requestCodeActionResolve(doc: Document, action: cn.enaium.lsp.model.CodeAction, onResult: (cn.enaium.lsp.model.CodeAction?) -> Unit) {
        val client = matchingClient(doc)
        if (client != null) client.requestCodeActionResolve(action, onResult) else onResult(null)
    }

    /** Inlay hints for [doc]; result on the render thread. */
    fun requestInlayHints(doc: Document, onResult: (List<cn.enaium.lsp.model.InlayHint>) -> Unit) {
        val client = matchingClient(doc)
        if (client != null) client.requestInlayHints(doc, onResult) else onResult(emptyList())
    }

    /** Code lenses for [doc]; result on the render thread. */
    fun requestCodeLens(doc: Document, onResult: (List<cn.enaium.lsp.model.CodeLens>) -> Unit) {
        val client = matchingClient(doc)
        if (client != null) client.requestCodeLens(doc, onResult) else onResult(emptyList())
    }

    /** Fold ranges for [doc]; result on the render thread. */
    fun requestFoldingRange(doc: Document, onResult: (List<cn.enaium.lsp.model.FoldingRange>) -> Unit) {
        val client = matchingClient(doc)
        if (client != null) client.requestFoldingRange(doc, onResult) else onResult(emptyList())
    }

    /** Resolves a code lens command. */
    fun requestCodeLensResolve(doc: Document, lens: cn.enaium.lsp.model.CodeLens, onResult: (cn.enaium.lsp.model.CodeLens?) -> Unit) {
        val client = matchingClient(doc)
        if (client != null) client.requestCodeLensResolve(lens, onResult) else onResult(null)
    }

    /** Workspace symbol search across every running server. */
    fun requestWorkspaceSymbols(query: String, onResult: (List<cn.enaium.lsp.model.WorkspaceSymbol>) -> Unit) {
        val all = ArrayList<cn.enaium.lsp.model.WorkspaceSymbol>()
        val running = clients.values.filter { it.isRunning }
        if (running.isEmpty()) {
            onResult(emptyList())
            return
        }
        var remaining = running.size
        for (client in running) {
            client.requestWorkspaceSymbols(query) { symbols ->
                all.addAll(symbols)
                remaining--
                if (remaining == 0) onResult(all)
            }
        }
    }

    /** References to the symbol under [pos] in [doc]. */
    fun requestReferences(doc: Document, pos: Position, onResult: (List<cn.enaium.lsp.model.Location>) -> Unit) {
        val client = matchingClient(doc)
        if (client != null) client.requestReferences(doc, pos, onResult) else onResult(emptyList())
    }

    /** Renames the symbol under [pos] in [doc] to [newName]. */
    fun requestRename(doc: Document, pos: Position, newName: String, onResult: (cn.enaium.lsp.model.WorkspaceEdit?) -> Unit) {
        val client = matchingClient(doc)
        if (client != null) client.requestRename(doc, pos, newName, onResult) else onResult(null)
    }

    /** Signature help at [pos] in [doc]. */
    fun requestSignatureHelp(doc: Document, pos: Position, onResult: (cn.enaium.lsp.model.SignatureHelp?) -> Unit) {
        val client = matchingClient(doc)
        if (client != null) client.requestSignatureHelp(doc, pos, onResult) else onResult(null)
    }

    fun shutdownAll() {
        stopJobs.values.forEach { it.cancel() }
        stopJobs.clear()
        clients.values.forEach { it.shutdownNow() }
        clients.clear()
        scope.cancel()
    }
}