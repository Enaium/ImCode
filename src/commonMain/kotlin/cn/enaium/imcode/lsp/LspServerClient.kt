package cn.enaium.imcode.lsp

import cn.enaium.imcode.app.LogLevel
import cn.enaium.imcode.app.Mailbox
import cn.enaium.imcode.app.OutputLog
import cn.enaium.imcode.config.LspServer
import cn.enaium.imcode.editor.Document
import cn.enaium.lsp.edit.EditOp
import cn.enaium.imcode.platform.Platform
import cn.enaium.imcode.platform.PlatformProcess
import cn.enaium.imcode.platform.ioFile
import cn.enaium.imcode.platform.launchRpcProcess
import cn.enaium.imcode.util.Glob
import cn.enaium.imcode.util.ShellWords
import cn.enaium.imcode.util.Uri
import cn.enaium.lsp.jsonrpc.JsonRpcLauncher
import cn.enaium.lsp.model.ClientCapabilities
import cn.enaium.lsp.model.ClientInfo
import cn.enaium.lsp.model.CompletionCapabilities
import cn.enaium.lsp.model.CompletionContext
import cn.enaium.lsp.model.CompletionItemCapabilities
import cn.enaium.lsp.model.CompletionParams
import cn.enaium.lsp.model.CompletionResult
import cn.enaium.lsp.model.CompletionTriggerKind
import cn.enaium.lsp.model.DefinitionParams
import cn.enaium.lsp.model.Diagnostic
import cn.enaium.lsp.model.DidChangeTextDocumentParams
import cn.enaium.lsp.model.DidCloseTextDocumentParams
import cn.enaium.lsp.model.DidOpenTextDocumentParams
import cn.enaium.lsp.model.DidSaveTextDocumentParams
import cn.enaium.lsp.model.Hover
import cn.enaium.lsp.model.HoverCapabilities
import cn.enaium.lsp.model.HoverContents
import cn.enaium.lsp.model.HoverParams
import cn.enaium.lsp.model.InitializeParams
import cn.enaium.lsp.model.InitializeResult
import cn.enaium.lsp.model.InitializedParams
import cn.enaium.lsp.model.Location
import cn.enaium.lsp.model.LocationResult
import cn.enaium.lsp.model.MarkedStringOrString
import cn.enaium.lsp.model.MessageParams
import cn.enaium.lsp.model.Position
import cn.enaium.lsp.model.PublishDiagnosticsCapabilities
import cn.enaium.lsp.model.PublishDiagnosticsParams
import cn.enaium.lsp.model.Save
import cn.enaium.lsp.model.ServerCapabilities
import cn.enaium.lsp.model.SynchronizationCapabilities
import cn.enaium.lsp.model.TextDocumentClientCapabilities
import cn.enaium.lsp.model.TextDocumentContentChangeEvent
import cn.enaium.lsp.model.TextDocumentIdentifier
import cn.enaium.lsp.model.TextDocumentItem
import cn.enaium.lsp.model.TextDocumentSync
import cn.enaium.lsp.model.VersionedTextDocumentIdentifier
import cn.enaium.lsp.model.WorkspaceClientCapabilities
import cn.enaium.lsp.model.WorkspaceFolder
import cn.enaium.lsp.model.ProgressParams
import cn.enaium.lsp.model.ProgressValue
import cn.enaium.lsp.model.Token
import cn.enaium.lsp.model.WindowClientCapabilities
import cn.enaium.lsp.model.WorkDoneProgressNotificationValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * One language server process and its JSON-RPC connection.
 *
 * Protocol reads run on a dedicated coroutine ([JsonRpcLauncher.listen]);
 * all writes/requests are serialized onto a single-thread dispatcher, and
 * results are delivered back through [Mailbox] so the render thread stays the
 * only mutator of UI state.
 */
/** One active work-done progress entry reported by a language server. */
data class LspProgress(val title: String, val message: String?, val percentage: Int?)

class LspServerClient(
    val config: LspServer,
    private val rpcLoggingEnabled: () -> Boolean,
    private val mailbox: Mailbox,
) {
    enum class State { IDLE, STARTING, RUNNING, STOPPING }

    @Volatile
    var state: State = State.IDLE
        private set

    @Volatile
    var statusText: String = "not started"
        private set

    @Volatile
    var capabilities: ServerCapabilities? = null
        private set

    @Volatile
    var rootFolder: String? = null
        private set

    private val tag get() = "LSP:${config.name}"

    /**
     * Active work-done progress keyed by token; written through [mailbox] and
     * read on the render thread, so it needs no lock of its own.
     */
    private val activeProgress = LinkedHashMap<String, LspProgress>()

    /** Work-done progress reported by the server (UI reads it each frame). */
    fun activeProgress(): List<LspProgress> = activeProgress.values.toList()

    private var process: PlatformProcess? = null
    @Volatile
    private var closing = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val io = Dispatchers.IO.limitedParallelism(1)

    private val opened = HashSet<String>()           // uris this server has seen didOpen for
    private val docVersions = HashMap<String, Int>() // per-uri protocol version

    /**
     * Protocol client from lsp-edit: every request/notification below
     * delegates to it. This class keeps the IDE-side concerns — process
     * launch, logging, progress display and document bookkeeping.
     */
    @Volatile
    private var client: cn.enaium.lsp.edit.lsp.LspClient? = null

    /** Trigger characters the server wants completion on, from initialize. */
    @Volatile
    var triggerCharacters: Set<String> = emptySet()
        private set

    @Volatile
    var saveIncludeText: Boolean = false
        private set

    @Volatile
    var semanticSupported: Boolean = false
        private set

    @Volatile
    var semanticTokenTypes: List<String> = emptyList()
        private set

    /** The server's semantic-token legend (types + modifiers). */
    @Volatile
    var semanticLegend: cn.enaium.lsp.model.SemanticTokensLegend? = null
        private set

    val isRunning: Boolean get() = state == State.RUNNING

    // ==================== lifecycle ====================

    /** Starts the process, runs initialize/initialized, then opens any matched docs. */
    fun start(rootFolder: String, initialDocs: List<Document>) {
        if (state != State.IDLE && state != State.STOPPING) return
        this.rootFolder = rootFolder
        state = State.STARTING
        statusText = "starting"
        closing = false
        scope.launch(io) {
            try {
                startInternal(initialDocs)
            } catch (t: Throwable) {
                OutputLog.error(tag, "start failed: ${t.message}")
                statusText = "failed: ${t.message}"
                state = State.IDLE
                destroyProcess()
            }
        }
    }

    private suspend fun startInternal(initialDocs: List<Document>) {
        val root = rootFolder ?: return
        val command = config.commandString
            ?.let { cn.enaium.imcode.util.ShellWords.split(it).ifEmpty { null } }
            ?: config.command.ifEmpty { listOf("kotlin-lsp") }
        // Show the verbatim command string when present so the log mirrors
        // exactly what the user typed (quotes included); otherwise fall
        // back to the normalized join of the argv tokens.
        val display = config.commandString ?: ShellWords.join(command)
        OutputLog.info(tag, "starting: $display (root=$root)")

        val proc = launchRpcProcess(
            command = command,
            cwd = if (ioFile(root).isDirectory) root else Platform.userHome,
            env = config.env,
            onStderr = { line -> if (line.isNotBlank()) OutputLog.append(tag, LogLevel.SERVER, line) },
        )
        process = proc

        val transport = RpcLogTransport(proc.transport, config.name, rpcLoggingEnabled)
        val c = cn.enaium.lsp.edit.lsp.LspClient(transport)
        client = c
        registerServerHandlers(c)

        scope.launch {
            try {
                c.startListening()
            } catch (t: Throwable) {
                if (!closing) OutputLog.warn(tag, "connection closed: ${t.message}")
            }
            if (!closing) {
                mailbox.post {
                    if (state == State.RUNNING) {
                        OutputLog.info(tag, "server exited")
                        state = State.IDLE
                        statusText = "exited"
                    }
                }
            }
        }

        val params = InitializeParams(
            processId = Platform.processId(),
            rootUri = Uri.pathToUri(root),
            rootPath = root,
            workspaceFolders = listOf(WorkspaceFolder(Uri.pathToUri(root), ioFile(root).name)),
            capabilities = ClientCapabilities(
                textDocument = TextDocumentClientCapabilities(
                    synchronization = SynchronizationCapabilities(didSave = true, willSave = false),
                    completion = CompletionCapabilities(
                        completionItem = CompletionItemCapabilities(
                            documentationFormat = listOf("plaintext", "markdown"),
                            resolveSupport = cn.enaium.lsp.model.CompletionItemResolveSupportCapabilities(
                                // kotlin-lsp defers detail/documentation/additionalTextEdits
                                // to item/resolve when these are advertised; the app
                                // resolves the visible rows eagerly (see eagerResolve)
                                properties = listOf("additionalTextEdits", "detail", "documentation"),
                            ),
                        ),
                        contextSupport = true,
                    ),
                    hover = HoverCapabilities(contentFormat = listOf("plaintext", "markdown")),
                    publishDiagnostics = PublishDiagnosticsCapabilities(relatedInformation = true),
                    inlayHint = cn.enaium.lsp.model.InlayHintCapabilities(
                        resolveSupport = cn.enaium.lsp.model.InlayHintResolveSupportCapabilities(
                            properties = listOf("tooltip", "textEdits", "label.location", "label.command"),
                        ),
                    ),
                ),
                workspace = WorkspaceClientCapabilities(
                    workspaceFolders = true,
                    inlayHint = cn.enaium.lsp.model.InlayHintWorkspaceCapabilities(refreshSupport = true),
                ),
                window = WindowClientCapabilities(workDoneProgress = true),
            ),
            // kotlin-lsp fills `additionalTextEdits` (auto-imports) in
            // completionItem/resolve ONLY when the client identifies as
            // "JetBrains Air"; other clients are expected to execute the
            // jetbrains.kotlin.completion.apply command, which answers
            // -32601 to generic LSP clients. Identify as Air so resolve
            // returns the full edit (insertion + imports).
            clientInfo = ClientInfo("JetBrains Air", "0.1.0"),
            trace = "off",
        )

        val result: InitializeResult = c.initialize(
            processId = params.processId,
            rootUri = params.rootUri,
            rootPath = params.rootPath,
            clientName = params.clientInfo?.name ?: "JetBrains Air",
            clientVersion = params.clientInfo?.version,
            capabilities = params.capabilities,
            workspaceFolders = params.workspaceFolders,
            trace = params.trace,
        )
        capabilities = result.capabilities
        val caps = result.capabilities
        triggerCharacters = caps.completionProvider?.triggerCharacters?.toSet() ?: emptySet()
        saveIncludeText = syncSaveIncludeText(caps)
        semanticSupported = caps.semanticTokensProvider != null
        semanticLegend = caps.semanticTokensProvider?.legend
        semanticTokenTypes = caps.semanticTokensProvider?.legend?.tokenTypes ?: emptyList()

        c.notifyInitialized()
        state = State.RUNNING
        statusText = "running"
        OutputLog.info(
            tag,
            "initialized (completion=${caps.completionProvider != null}, hover=${caps.hoverProvider != null}, semantic=${semanticSupported})",
        )
        mailbox.post { onServerReady?.invoke() }

        // sync already-open matching documents
        for (doc in initialDocs) {
            if (matches(doc.path)) didOpen(doc)
        }
    }

    private fun syncSaveIncludeText(caps: ServerCapabilities): Boolean {
        val sync = caps.textDocumentSync
        if (sync !is TextDocumentSync.Options) return false
        val save = sync.value.save ?: return true // save:true (bare) means include text edits? no -> false by default
        return when (save) {
            is Save.Options -> save.value.includeText ?: false
            is Save.Enabled -> save.value
        }
    }

    private fun registerServerHandlers(l: cn.enaium.lsp.edit.lsp.LspClient) {
        l.onLogMessage { p ->
            val level = when (p.type) {
                1 -> LogLevel.ERROR
                2 -> LogLevel.WARN
                else -> LogLevel.INFO
            }
            OutputLog.append(tag, level, p.message)
        }
        l.onShowMessage { p ->
            val level = when (p.type) {
                1 -> LogLevel.ERROR
                2 -> LogLevel.WARN
                else -> LogLevel.INFO
            }
            OutputLog.append(tag, level, "[showMessage] ${p.message}")
        }
        l.onPublishDiagnostics { p ->
            val path = Uri.uriToPath(p.uri) ?: p.uri
            mailbox.post { onDiagnostics?.invoke(path, p.version, p.diagnostics) }
        }
        l.onProgress { p ->
            val token = when (val t = p.token) {
                is Token.StringValue -> t.value
                is Token.NumberValue -> t.value.toString()
                null -> return@onProgress
            }
            val value = (p.value as? ProgressValue.WorkDone)?.value ?: return@onProgress
            mailbox.post {
                when (value) {
                    is WorkDoneProgressNotificationValue.Begin -> activeProgress[token] =
                        LspProgress(value.value.title, value.value.message, value.value.percentage)

                    is WorkDoneProgressNotificationValue.Report -> {
                        val prev = activeProgress[token]
                        activeProgress[token] = LspProgress(
                            prev?.title ?: "",
                            value.value.message ?: prev?.message,
                            value.value.percentage ?: prev?.percentage,
                        )
                    }

                    is WorkDoneProgressNotificationValue.End -> activeProgress.remove(token)
                }
            }
        }
        // JetBrains-style servers report build/import progress through this
        // custom notification instead of $/progress; surface it in the LSP
        // output tab so long imports are not silent.
        l.onCustomNotification("intellij/importLog") { el ->
            val message = (el as? kotlinx.serialization.json.JsonObject)
                ?.get("message")?.toString()?.trim('"')
            if (!message.isNullOrBlank()) {
                OutputLog.append(tag, LogLevel.SERVER, message.trim())
            }
        }
        l.onCustomNotification("telemetry/event") { /* ignored */ }
        // Server-initiated edits are applied by the host.
        l.onApplyEdit = { edit ->
            mailbox.post { onApplyEdit?.invoke(edit) }
            true
        }
    }

    /** The protocol client (lsp-edit); hosts bind editors to it. */
    val lspClient: cn.enaium.lsp.edit.lsp.LspClient? get() = client

    /** Diagnostics sink and workspace-edit sink, provided by the app (called on render thread). */
    var onDiagnostics: ((path: String, version: Int?, diagnostics: List<Diagnostic>) -> Unit)? = null
    var onApplyEdit: ((Any) -> Unit)? = null

    /** Fired on the render thread once initialize/initialized completed. */
    var onServerReady: (() -> Unit)? = null

    /** Stops the server: shutdown request, exit notification, process destroy. */
    fun stop() {
        if (state == State.IDLE || state == State.STOPPING) return
        state = State.STOPPING
        statusText = "stopping"
        closing = true
        scope.launch(io) {
            try {
                withTimeoutOrNull(3000) {
                    client?.shutdown()
                }
                client?.exit()
            } catch (_: Throwable) {
            }
            destroyProcess()
            mailbox.post {
                state = State.IDLE
                statusText = "stopped"
                opened.clear()
                docVersions.clear()
                OutputLog.info(tag, "stopped")
            }
        }
    }

    private fun destroyProcess() {
        val proc = process
        process = null
        if (proc != null) {
            try { proc.destroy() } catch (_: Throwable) {}
            try { proc.waitFor(2000) } catch (_: Throwable) {}
            try { proc.destroy() } catch (_: Throwable) {}
        }
    }

    fun shutdownNow() {
        closing = true
        scope.cancel()
        process?.destroy()
        state = State.IDLE
        statusText = "stopped"
    }

    // ==================== document sync ====================

    fun matches(path: String): Boolean {
        val fileName = ioFile(path).name
        val ws = rootFolder
        val rel = ws?.let { runCatching { ioFile(ws).relativize(ioFile(path)) }.getOrNull() }
        return Glob.matchesAny(config.patterns, fileName, rel)
    }

    private fun languageIdFor(doc: Document): String = config.languageId ?: doc.languageId

    fun didOpen(doc: Document) {
        if (!isRunning && state != State.STARTING) return
        scope.launch(io) {
            val uri = doc.uri
            if (uri in opened) return@launch
            opened.add(uri)
            docVersions[uri] = 1
            try {
                client?.didOpen(uri, languageIdFor(doc), 1, doc.text())
            } catch (t: Throwable) {
                OutputLog.warn(tag, "didOpen failed: ${t.message}")
            }
        }
    }

    /**
     * Incremental sync: converts a batch of editor transactions into LSP
     * content-change events (positions are 0-based, matching LSP directly)
     * and bumps the per-uri protocol version once per notification.
     */
    fun didChangeIncremental(doc: Document, ops: List<EditOp>) {
        if (!isRunning) return
        scope.launch(io) {
            val uri = doc.uri
            if (uri !in opened) return@launch
            val version = (docVersions[uri] ?: 1) + 1
            docVersions[uri] = version
            val events = ops.map { op ->
                TextDocumentContentChangeEvent(
                    range = cn.enaium.lsp.model.Range(
                        cn.enaium.lsp.model.Position(op.pos.line, op.pos.index),
                        if (op.insert) {
                            cn.enaium.lsp.model.Position(op.pos.line, op.pos.index)
                        } else {
                            cn.enaium.lsp.model.Position(
                                op.pos.line,
                                op.pos.index + op.text.length,
                            )
                        },
                    ),
                    text = if (op.insert) op.text else "",
                )
            }
            if (events.isEmpty()) return@launch
            try {
                client?.didChange(uri, version, events)
            } catch (t: Throwable) {
                OutputLog.warn(tag, "didChange failed: ${t.message}")
            }
        }
    }

    fun didClose(path: String, uri: String) {
        scope.launch(io) {
            if (uri !in opened) return@launch
            opened.remove(uri)
            docVersions.remove(uri)
            try {
                client?.didClose(uri)
            } catch (_: Throwable) {
            }
        }
    }

    fun didSave(doc: Document, text: String) {
        if (!isRunning) return
        scope.launch(io) {
            try {
                client?.didSave(doc.uri, if (saveIncludeText) text else null)
            } catch (_: Throwable) {
            }
        }
    }

    // ==================== requests ====================

    /** Completion; result items are posted to [mailbox] on the render thread. */
    fun requestCompletion(doc: Document, position: Position, triggerChar: String?, onResult: (List<cn.enaium.lsp.model.CompletionItem>) -> Unit) {
        if (!isRunning) {
            // MUST complete: the caller uses the callback to release its
            // in-flight guard (otherwise every later request is dropped and
            // the popup waits forever on a never-fulfilled promise)
            mailbox.post { onResult(emptyList()) }
            return
        }
        scope.launch(io) {
            try {
                val result = client?.completion(doc.uri, position)
                val items = when (result) {
                    is CompletionResult.Items -> result.value
                    is CompletionResult.ListValue -> result.value.items
                    else -> emptyList()
                }
                mailbox.post { onResult(items) }
            } catch (_: Throwable) {
                mailbox.post { onResult(emptyList()) }
            }
        }
    }

    /**
     * completionItem/resolve: kotlin-lsp defers additionalTextEdits /
     * documentation to the resolve round-trip when the client advertises
     * resolveSupport. Used on accept so auto-imports always arrive.
     */
    fun requestCompletionResolve(item: cn.enaium.lsp.model.CompletionItem, onResult: (cn.enaium.lsp.model.CompletionItem?) -> Unit) {
        if (!isRunning) {
            mailbox.post { onResult(null) }
            return
        }
        scope.launch(io) {
            try {
                val result = client?.completionResolve(item)
                mailbox.post { onResult(result) }
            } catch (_: Throwable) {
                mailbox.post { onResult(null) }
            }
        }
    }

    /** Hover text (markup stripped); null when empty. */
    fun requestHover(doc: Document, position: Position, onResult: (String?) -> Unit) {
        if (!isRunning) {
            mailbox.post { onResult(null) }
            return
        }
        scope.launch(io) {
            try {
                val hover = client?.hover(doc.uri, position)
                mailbox.post { onResult(hover?.contents?.let(::hoverText)) }
            } catch (_: Throwable) {
                mailbox.post { onResult(null) }
            }
        }
    }

    private fun hoverText(contents: Any): String? {
        val result = when (contents) {
            is HoverContents.Markup -> contents.value.value
            is HoverContents.MarkedStrings -> {
                val sb = StringBuilder()
                for (s in contents.value) {
                    val part = when (s) {
                        is MarkedStringOrString.StringValue -> s.value
                        is MarkedStringOrString.Marked -> s.value.value
                    }
                    if (part.isNotEmpty()) {
                        if (sb.isNotEmpty()) sb.append('\n')
                        sb.append(part)
                    }
                }
                sb.toString()
            }
            else -> ""
        }
        return result.trim().ifEmpty { null }
    }

    /** Go-to-definition; first target location, or null. */
    fun requestDefinition(doc: Document, position: Position, onResult: (Location?) -> Unit) {
        if (!isRunning) {
            mailbox.post { onResult(null) }
            return
        }
        scope.launch(io) {
            try {
                val result = client?.definition(doc.uri, position)
                val loc = when (result) {
                    is LocationResult.Locations -> result.value.firstOrNull()
                    is LocationResult.Links -> result.value.firstOrNull()?.let { link ->
                        Location(link.targetUri, link.targetSelectionRange)
                    }
                    else -> null
                }
                mailbox.post { onResult(loc) }
            } catch (_: Throwable) {
                mailbox.post { onResult(null) }
            }
        }
    }

    /**
     * `workspace/executeCommand`: runs a server command and returns its raw
     * JSON result on the render thread (JetBrains-style completion items
     * return a WorkspaceEdit here containing the real insertion + imports).
     */
    fun requestExecuteCommand(
        command: String,
        arguments: List<JsonElement>?,
        onResult: (JsonElement?) -> Unit,
    ) {
        if (!isRunning) {
            mailbox.post { onResult(null) }
            return
        }
        scope.launch(io) {
            try {
                client?.executeCommand(command, arguments)
                mailbox.post { onResult(null) }
            } catch (_: Throwable) {
                mailbox.post { onResult(null) }
            }
        }
    }

    /** Full-document semantic tokens; result posted on the render thread. */
    fun requestSemanticTokensFull(doc: Document, onResult: (cn.enaium.lsp.model.SemanticTokens?) -> Unit) {
        if (!isRunning) {
            mailbox.post { onResult(null) }
            return
        }
        scope.launch(io) {
            try {
                val result = client?.semanticTokensFull(doc.uri)
                mailbox.post { onResult(result) }
            } catch (_: Throwable) {
                mailbox.post { onResult(null) }
            }
        }
    }

    // ==================== structure / lenses / symbols ====================

    /** Document symbol tree; result posted on the render thread. */
    fun requestDocumentSymbols(doc: Document, onResult: (List<cn.enaium.lsp.model.DocumentSymbol>) -> Unit) {
        if (!isRunning) {
            mailbox.post { onResult(emptyList()) }
            return
        }
        scope.launch(io) {
            try {
                val symbols = client?.documentSymbols(doc.uri) ?: emptyList()
                mailbox.post {
                    OutputLog.append("LSP", LogLevel.LSP, "documentSymbol: ${symbols.size} symbol(s)")
                    onResult(symbols)
                }
            } catch (t: Throwable) {
                mailbox.post {
                    OutputLog.append("LSP", LogLevel.LSP, "documentSymbol failed: ${t.message}")
                    onResult(emptyList())
                }
            }
        }
    }

    /**
     * Code actions (quick fixes/refactors) for [range]; result posted on the
     * render thread. [diagnostics] are the ones overlapping the range — the
     * server needs them to compute quick fixes.
     */
    fun requestCodeActions(
        doc: Document,
        range: cn.enaium.lsp.model.Range,
        diagnostics: List<Diagnostic>,
        onResult: (List<cn.enaium.lsp.model.CodeAction>) -> Unit,
    ) {
        if (!isRunning) {
            mailbox.post { onResult(emptyList()) }
            return
        }
        scope.launch(io) {
            try {
                val actions = client?.codeAction(doc.uri, range, diagnostics) ?: emptyList()
                mailbox.post {
                    OutputLog.append("LSP", LogLevel.LSP, "codeAction: ${actions.size} action(s)")
                    onResult(actions)
                }
            } catch (t: Throwable) {
                mailbox.post {
                    OutputLog.append("LSP", LogLevel.LSP, "codeAction failed: ${t.message}")
                    onResult(emptyList())
                }
            }
        }
    }

    /** codeAction/resolve: fills edit/command of a lazily-resolved action. */
    fun requestCodeActionResolve(action: cn.enaium.lsp.model.CodeAction, onResult: (cn.enaium.lsp.model.CodeAction?) -> Unit) {
        if (!isRunning) {
            mailbox.post { onResult(null) }
            return
        }
        scope.launch(io) {
            try {
                val result = client?.codeActionResolve(action)
                mailbox.post { onResult(result) }
            } catch (_: Throwable) {
                mailbox.post { onResult(null) }
            }
        }
    }

    /** Inlay hints for the whole document; result posted on the render thread. */
    fun requestInlayHints(doc: Document, onResult: (List<cn.enaium.lsp.model.InlayHint>) -> Unit) {
        if (!isRunning) {
            mailbox.post { onResult(emptyList()) }
            return
        }
        scope.launch(io) {
            try {
                val end = cn.enaium.lsp.model.Position(
                    doc.editor.buffer.lineCount(),
                    0,
                )
                val hints = client?.inlayHint(
                    doc.uri,
                    cn.enaium.lsp.model.Range(cn.enaium.lsp.model.Position(0, 0), end),
                ) ?: emptyList()
                mailbox.post { onResult(hints) }
            } catch (_: Throwable) {
                mailbox.post { onResult(emptyList()) }
            }
        }
    }

    /** Code lenses for a document; result posted on the render thread. */
    fun requestCodeLens(doc: Document, onResult: (List<cn.enaium.lsp.model.CodeLens>) -> Unit) {
        if (!isRunning) {
            mailbox.post { onResult(emptyList()) }
            return
        }
        scope.launch(io) {
            try {
                val result = client?.codeLens(doc.uri) ?: emptyList()
                mailbox.post { onResult(result) }
            } catch (_: Throwable) {
                mailbox.post { onResult(emptyList()) }
            }
        }
    }

    /** Fold ranges for a document; result posted on the render thread. */
    fun requestFoldingRange(doc: Document, onResult: (List<cn.enaium.lsp.model.FoldingRange>) -> Unit) {
        if (!isRunning) {
            mailbox.post { onResult(emptyList()) }
            return
        }
        scope.launch(io) {
            try {
                val result = client?.foldingRange(doc.uri) ?: emptyList()
                mailbox.post { onResult(result) }
            } catch (_: Throwable) {
                mailbox.post { onResult(emptyList()) }
            }
        }
    }

    /** codeLens/resolve: fills the command of a lens. */
    fun requestCodeLensResolve(lens: cn.enaium.lsp.model.CodeLens, onResult: (cn.enaium.lsp.model.CodeLens?) -> Unit) {
        if (!isRunning) {
            mailbox.post { onResult(null) }
            return
        }
        scope.launch(io) {
            try {
                val result = client?.codeLensResolve(lens)
                mailbox.post { onResult(result) }
            } catch (_: Throwable) {
                mailbox.post { onResult(null) }
            }
        }
    }

    /** Workspace symbol search; result posted on the render thread. */
    fun requestWorkspaceSymbols(query: String, onResult: (List<cn.enaium.lsp.model.WorkspaceSymbol>) -> Unit) {
        if (!isRunning) {
            mailbox.post { onResult(emptyList()) }
            return
        }
        scope.launch(io) {
            try {
                val result = client?.workspaceSymbols(query) ?: emptyList()
                mailbox.post { onResult(result) }
            } catch (_: Throwable) {
                mailbox.post { onResult(emptyList()) }
            }
        }
    }

    // ==================== references / rename / signature ====================

    /** References to the symbol at [position]; result posted on the render thread. */
    fun requestReferences(doc: Document, position: Position, onResult: (List<Location>) -> Unit) {
        if (!isRunning) {
            mailbox.post { onResult(emptyList()) }
            return
        }
        scope.launch(io) {
            try {
                val result = client?.references(doc.uri, position) ?: emptyList()
                mailbox.post { onResult(result) }
            } catch (_: Throwable) {
                mailbox.post { onResult(emptyList()) }
            }
        }
    }

    /** Renames the symbol at [position] to [newName]; result posted on the render thread. */
    fun requestRename(doc: Document, position: Position, newName: String, onResult: (cn.enaium.lsp.model.WorkspaceEdit?) -> Unit) {
        if (!isRunning) {
            mailbox.post { onResult(null) }
            return
        }
        scope.launch(io) {
            try {
                val result = client?.rename(doc.uri, position, newName)
                mailbox.post { onResult(result) }
            } catch (_: Throwable) {
                mailbox.post { onResult(null) }
            }
        }
    }

    /** Signature help at [position]; result posted on the render thread. */
    fun requestSignatureHelp(doc: Document, position: Position, onResult: (cn.enaium.lsp.model.SignatureHelp?) -> Unit) {
        if (!isRunning) {
            mailbox.post { onResult(null) }
            return
        }
        scope.launch(io) {
            try {
                val result = client?.signatureHelp(doc.uri, position)
                mailbox.post { onResult(result) }
            } catch (_: Throwable) {
                mailbox.post { onResult(null) }
            }
        }
    }

}