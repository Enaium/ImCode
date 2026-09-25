package cn.enaium.imcode.app

import cn.enaium.imcode.config.DebugAdapterConfig
import cn.enaium.imcode.editor.Document
import cn.enaium.imcode.platform.launchRpcProcess
import cn.enaium.imcode.util.Glob
import cn.enaium.imcode.platform.ioFile
import cn.enaium.lsp.dap.DebugClientLauncher
import cn.enaium.lsp.dap.model.SourceBreakpoint
import cn.enaium.lsp.edit.dap.DapSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Runs debug sessions: picks the adapter configured for a file, spawns it,
 * drives a [DapSession] over its stdio, and keeps the editor's breakpoints and
 * the adapter's verdicts in sync.
 *
 * The editor owns breakpoints (the gutter toggles them); the adapter judges
 * them. Every change goes to the adapter, and its verdicts come back into the
 * gutter as the marker state, which is what the icons show.
 */
class DebugController(private val core: AppCore) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }

    /** The running session, null when nothing is being debugged. */
    var session: DapSession? = null
        private set

    /** The adapter driving [session]. */
    var adapterName: String = ""
        private set

    /** Last status line for the toolbar (startup failures, exit reasons). */
    var status: String = ""

    private var process: cn.enaium.imcode.platform.PlatformProcess? = null
    private var debuggedPath: String? = null

    /**
     * The editor of the debugged document. Held directly: looking it up by
     * path later would depend on the tab still being open, and the verdicts
     * have to be cleared even when the user has moved on to another file.
     */
    private var debuggedEditor: cn.enaium.lsp.edit.Editor? = null

    /** True while a session exists (running or paused). */
    val active: Boolean get() = session != null

    /** True while the debuggee runs; false while it is stopped at a breakpoint. */
    val running: Boolean get() = session?.running == true

    /** True when a session exists and the debuggee is stopped. */
    val paused: Boolean get() = session?.let { !it.running } == true

    /** The adapter configured for [doc], if any. */
    fun adapterFor(doc: Document): DebugAdapterConfig? {
        val name = ioFile(doc.path).name
        val workspace = core.workspaceFor(doc.path)?.dir
        val rel = workspace?.let { ioFile(it).relativize(ioFile(doc.path)) }
        return core.config.debugAdapters.firstOrNull { Glob.matchesAny(it.patterns, name, rel) }
    }

    /** True when [doc] has an adapter, i.e. it can be debugged. */
    fun canDebug(doc: Document?): Boolean = doc != null && adapterFor(doc) != null

    // ==================== Session lifecycle ====================

    /**
     * Starts debugging [doc]: spawns its adapter and launches the debuggee.
     * Existing breakpoints are sent before the program starts running.
     */
    fun start(doc: Document) {
        if (active) return
        val adapter = adapterFor(doc)
        if (adapter == null) {
            status = "no debug adapter matches ${ioFile(doc.path).name}"
            return
        }
        val workspace = core.workspaceFor(doc.path)?.dir
        val command = adapter.command
        if (command.isEmpty()) {
            status = "adapter ${adapter.name} has no command"
            return
        }
        status = "starting ${adapter.name}..."
        val spawned = try {
            launchRpcProcess(command, cwd = workspace, env = adapter.env) { line ->
                OutputLog.info("DAP:${adapter.name}", line)
            }
        } catch (e: Throwable) {
            status = "could not start ${adapter.name}: ${e.message}"
            OutputLog.info("DAP", status)
            return
        }
        process = spawned
        debuggedPath = doc.path
        debuggedEditor = doc.editor
        adapterName = adapter.name

        val session = DapSession(
            editor = doc.editor,
            client = DebugClientLauncher(spawned.transport),
            sourceName = ioFile(doc.path).name,
            sourcePath = doc.path,
        )
        this.session = session
        OutputLog.info("DAP", "${adapter.name} started for ${doc.path}")

        scope.launch {
            try {
                // Breakpoints go in with the launch: configurationDone starts
                // the debuggee, so anything sent afterwards is too late.
                session.start(
                    breakpoints = sourceBreakpoints(doc),
                    launchArgs = launchArgs(adapter, doc, workspace),
                )
                status = ""
                pushVerdicts(doc)
                OutputLog.info("DAP", "session running (adapter=${adapter.name})")
            } catch (e: Throwable) {
                status = "launch failed: ${e.message}"
                OutputLog.info("DAP", status)
                stop()
            }
        }
    }

    /** The `launch` arguments, with `$FILE`/`$DIR` substituted. */
    private fun launchArgs(
        adapter: DebugAdapterConfig,
        doc: Document,
        workspace: String?,
    ): kotlinx.serialization.json.JsonElement? {
        val text = adapter.launchArgs
            .replace("\$FILE", doc.path)
            .replace("\$DIR", workspace ?: ioFile(doc.path).parent?.absolutePath.orEmpty())
        if (text.isBlank()) return null
        return try {
            json.parseToJsonElement(text)
        } catch (e: Throwable) {
            status = "launch arguments are not valid JSON: ${e.message}"
            null
        }
    }

    /** Ends the session and kills the adapter process. */
    fun stop() {
        val current = session ?: return
        scope.launch {
            try {
                current.stop()
            } catch (_: Throwable) {
            }
        }
        current.close()
        process?.destroy()
        process = null
        session = null
        adapterName = ""
        status = ""
        debuggedPath = null
        // Nothing judges the breakpoints anymore: back to the plain marker.
        // The editor that was debugged, not whichever tab happens to be
        // active — the two differ as soon as another file is opened.
        debuggedEditor?.let { editor ->
            editor.setBreakpointMarkers(editor.getBreakpointMarkers().map { it.copy(verified = null) })
        }
        debuggedEditor = null
    }

    // ==================== Toolbar actions ====================

    /** Continue when stopped, pause when running. */
    fun continueOrPause() {
        val current = session ?: return
        scope.launch {
            try {
                if (current.running) current.pause() else current.continue_()
            } catch (e: Throwable) {
                status = "continue/pause failed: ${e.message}"
            }
        }
    }

    fun stepOver() = step("step over") { it.next() }

    fun stepInto() = step("step into") { it.stepIn() }

    fun stepOut() = step("step out") { it.stepOut() }

    fun restart() = step("restart") { it.restart() }

    private fun step(what: String, action: suspend (DapSession) -> Unit) {
        val current = session ?: return
        scope.launch {
            try {
                action(current)
            } catch (e: Throwable) {
                status = "$what failed: ${e.message}"
            }
        }
    }

    // ==================== Breakpoints ====================

    /** Called when the editor's gutter toggled a breakpoint. */
    fun onBreakpointsChanged(doc: Document) {
        if (session == null || doc.path != debuggedPath) return
        scope.launch {
            syncBreakpoints(doc)
        }
    }

    /**
     * Sends the editor's breakpoints to the adapter and shows its verdicts
     * back in the gutter: a marker the adapter verified draws differently from
     * one it rejected.
     */
    private suspend fun syncBreakpoints(doc: Document) {
        val current = session ?: return
        current.setSourceBreakpoints(sourceBreakpoints(doc))
        pushVerdicts(doc)
    }

    /** The editor's enabled breakpoints, as the adapter wants them. */
    private fun sourceBreakpoints(doc: Document): List<SourceBreakpoint> =
        doc.editor.getBreakpointMarkers().filter { it.enabled }.map { marker ->
            SourceBreakpoint(
                line = marker.line,
                logMessage = if (marker.logpoint) "line ${marker.line}" else null,
            )
        }

    /** Shows the adapter's verdicts in the gutter (verified / rejected). */
    private fun pushVerdicts(doc: Document) {
        val current = session ?: return
        val verdicts = current.breakpoints.mapNotNull { bp -> bp.line?.let { it to bp } }.toMap()
        doc.editor.setBreakpointMarkers(
            doc.editor.getBreakpointMarkers().map { marker ->
                val verdict = verdicts[marker.line] ?: return@map marker
                marker.copy(verified = verdict.verified)
            },
        )
    }

    /** Applies pending adapter state; called once per frame. */
    fun drain() {
        session?.drain()
    }

    /** The path currently being debugged (null when idle). */
    fun debuggedPath(): String? = debuggedPath
}
