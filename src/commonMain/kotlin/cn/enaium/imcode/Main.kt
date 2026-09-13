package cn.enaium.imcode

import cn.enaium.imcode.app.AppCore
import cn.enaium.imcode.app.Mailbox
import cn.enaium.imcode.app.OutputLog
import cn.enaium.imcode.app.WindowCtx
import cn.enaium.imcode.editor.AutoDebug
import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLEvent
import cn.enaium.sdl.SDLInitFlags
import cn.enaium.sdl.SDLWindowEventType

private class CliArgs(args: Array<String>) {
    var frames: Int = Int.MAX_VALUE
    var seconds: Int = 0
    var openPath: List<String> = emptyList()
    var lspLog: Boolean = false
    var showSettings: Boolean = false
    var rpcLog: Boolean? = null
    var foldTest: Boolean = false
    var typeTest: Boolean = false
    var autoDebug: Boolean = false
    var noCompletionUi: Boolean = false
    var noEditorRender: Boolean = false

    init {
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--frames" -> { frames = args.getOrNull(i + 1)?.toIntOrNull() ?: Int.MAX_VALUE; i++ }
                "--seconds" -> { seconds = args.getOrNull(i + 1)?.toIntOrNull() ?: 0; i++ }
                "--open" -> {
                    if (i + 1 < args.size) openPath = openPath + args[i + 1]
                    while (i + 2 < args.size && !args[i + 2].startsWith("--")) { openPath = openPath + args[i + 2]; i++ }
                    i++
                }
                "--lsp-log" -> lspLog = true
                "--settings" -> showSettings = true
                "--rpc-log" -> { rpcLog = args.getOrNull(i + 1)?.toBooleanStrictOrNull() ?: true; i++ }
                "--fold-test" -> foldTest = true
                "--type-test" -> typeTest = true
                "--auto-debug" -> autoDebug = true
                "--no-completion-ui" -> noCompletionUi = true
                "--no-editor-render" -> noEditorRender = true
            }
            i++
        }
    }
}

fun main(args: Array<String>) {
    val cli = CliArgs(args)
    println("ImCode starting (frames=${cli.frames})...")
    SDL.setMainReady()
    if (!SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)) {
        SDL.setHint("SDL_VIDEO_DRIVER", "dummy")
        if (!SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)) {
            error("SDL_Init failed: ${SDL.error()}")
        }
        println("video init fell back to the dummy driver - running headless")
    }
    println("SDL ${SDL.version()} (${SDL.revision()})")
    println("Video driver: ${SDL.getCurrentVideoDriver()}")

    val mailbox = Mailbox()
    val core = AppCore(mailbox)
    val windows = ArrayList<WindowCtx>()

    // window 0: the primary window. The first workspace is ADOPTED into it
    // (no blank hub window); further workspaces get their own SDL window.
    val primary = WindowCtx(isPrimary = true, workspace = null, core = core, width = 1280, height = 860)
    windows.add(primary)

    core.onOpenWorkspaceWindow = { ws ->
        val first = windows.first()
        if (first.workspace == null) {
            first.adopt(ws)
        } else {
            windows.add(WindowCtx(isPrimary = false, workspace = ws, core = core, width = 1100, height = 720))
        }
    }
    core.onFinalizeWorkspaceClose = { ws ->
        val w = windows.firstOrNull { it.workspace === ws }
        core.removeWorkspace(ws)
        when {
            w == null -> Unit
            w.isPrimary -> {
                w.release()
                core.showWelcome = true
            }
            else -> w.wantDestroy = true
        }
    }
    core.restoreWorkspaces()

    if (cli.lspLog) core.forceRpcLogging(true)
    cli.openPath.forEach { path -> core.openPathAtStartup(path) }
    if (cli.showSettings) core.showSettings = true
    if (cli.typeTest) {
        core.typeTestRemaining = 2
        AutoDebug = true
    }
    if (cli.autoDebug) {
        AutoDebug = true
        core.forceRpcLogging(true)
        println("auto-debug enabled: completion diagnostics + RPC frames will appear in the output log")
    }
    if (cli.noCompletionUi) {
        cn.enaium.imcode.editor.UiSwitches.completionUiEnabled = false
        println("completion popup UI disabled (experiment)")
    }
    if (cli.noEditorRender) {
        cn.enaium.imcode.editor.UiSwitches.editorRenderEnabled = false
        println("editor rendering disabled (experiment)")
    }
    OutputLog.info("App", "windows: 1 primary, ${core.workspaces.size} workspace(s)")

    var running = true
    var frame = 0
    // Mouse pointer visibility while typing: hidden during text input so it
    // cannot sit over the text (and its hover) being written, shown again on
    // the first mouse movement. SDL.showCursor() toggles and returns the new
    // state.
    var mouseCursorShown = true
    // Pointer position when the cursor was hidden; tiny jitter must not
    // bring it back while typing.
    var cursorHideX = 0f
    var cursorHideY = 0f
    var folded = false
    var typetestSeeded = false
    val startupMs = System.currentTimeMillis()
    val deadline = if (cli.seconds > 0) startupMs + cli.seconds * 1000L else Long.MAX_VALUE
    while (running && !core.canExitNow && frame < cli.frames && System.currentTimeMillis() < deadline) {
        while (true) {
            val event = SDL.pollEvent() ?: break
            when (event) {
                is SDLEvent.Quit -> core.requestExit()
                is SDLEvent.Window -> {
                    val ctx = windows.firstOrNull { it.windowId == event.windowId } ?: primary
                    if (event.type == SDLWindowEventType.CLOSE_REQUESTED) {
                        if (ctx.isPrimary) core.requestExit() else ctx.requestCloseWorkspace()
                    } else {
                        ctx.imgui.processEvent(event)
                    }
                }
                is SDLEvent.MouseWheel -> {
                    val id = event.windowId
                    val ctx = windows.firstOrNull { it.windowId == id } ?: primary
                    ctx.imgui.processEvent(event)
                    // Horizontal wheel: the scrollbar must move OPPOSITE to the
                    // trackpad swipe so the content follows the finger.
                    if (event.x != 0f) {
                        cn.enaium.imgui.ImGui.getIO().addMouseWheelEvent(-2f * event.x, 0f)
                    }
                }
                is SDLEvent.TextInput -> {
                    val id = event.windowId
                    val ctx = if (id != null) windows.firstOrNull { it.windowId == id } else null
                    (ctx ?: primary).imgui.processEvent(event)
                    // Pasting (Cmd/Ctrl+V) delivers the clipboard as a
                    // TextInput event AND as a key chord that the editor's
                    // handleKeyboard consumes via paste(); feeding the same
                    // text here would double-insert and split undo history.
                    val pasteChord = (cn.enaium.imgui.ImGui.isKeyDown(cn.enaium.imgui.ImGuiKey.MOD_CTRL) ||
                        cn.enaium.imgui.ImGui.isKeyDown(cn.enaium.imgui.ImGuiKey.MOD_SUPER)) &&
                        cn.enaium.imgui.ImGui.isKeyDown(cn.enaium.imgui.ImGuiKey.V)
                    if (pasteChord) {
                        // The editor handles it; do not queue the text again.
                    } else {
                        // Feed typed characters into the focused editor (the
                        // lsp-edit Editor reads text via queueTextInput, not
                        // the imgui IO queue).
                        val ws = ctx?.workspace
                        if (ws != null) {
                            val doc = ws.activeFile?.let { core.documents.get(it) }
                            if (doc != null && doc.editor.isFocused) {
                                doc.editor.queueTextInput(event.text)
                                if (mouseCursorShown) {
                                    val p = cn.enaium.imgui.ImGui.getMousePos()
                                    cursorHideX = p.x
                                    cursorHideY = p.y
                                    cn.enaium.sdl.SDL.hideCursor()
                                    mouseCursorShown = false
                                }
                            }
                        }
                    }
                }
                else -> {
                    if (event is SDLEvent.MouseMotion && !mouseCursorShown) {
                        // Restore only on a real movement (>8 px): pointer
                        // jitter while typing must keep it hidden.
                        val dx = event.x - cursorHideX
                        val dy = event.y - cursorHideY
                        if (dx * dx + dy * dy > 64f) {
                            cn.enaium.sdl.SDL.showCursor()
                            mouseCursorShown = true
                        }
                    }
                    val id = windowIdOf(event)
                    val ctx = if (id != null) windows.firstOrNull { it.windowId == id } else null
                    (ctx ?: primary).imgui.processEvent(event)
                }
            }
        }

        mailbox.drain()

        if (cli.foldTest && !folded && frame > 15) {
            folded = true
            val doc = core.activeDoc()
            if (doc != null) {
                val editor = doc.editor
                val fold = editor.foldAt(0) ?: editor.foldAt(1)
                if (fold != null) editor.toggleFold(fold.startLine)
                OutputLog.info("App", "fold-test: toggled fold at line 1")
            }
        }

        core.frameCount++
        for (w in windows.toList()) w.renderFrame()
        // a font-size change only needs one re-application per turn
        core.fontDirty = false

        for (w in windows.toList()) {
            if (w.wantDestroy) {
                w.closeRendering()
                windows.remove(w)
            }
        }
        frame++
    }

    if (cli.typeTest) {
        val doc = core.activeDoc()
        if (doc != null) {
            val text = doc.text()
            println("type-test: doc length=${text.length}, contains 'p'=${text.contains('p')}")
        }
    }
    if (cli.lspLog || cli.frames != Int.MAX_VALUE || cli.seconds > 0) {
        println("===== ImCode OutputLog dump (${OutputLog.snapshot().size} entries) =====")
        for (e in OutputLog.snapshot().takeLast(300)) {
            println("[${e.tag}] ${e.text}")
        }
    }
    if (cli.lspLog) core.restoreRpcLogging()
    core.shutdown()
    for (w in windows) w.closeRendering()

    SDL.quit()
    println("ImCode exited")
}

/** Returns the SDL window id carried by window-scoped events, or null. */
private fun windowIdOf(event: SDLEvent): Int? = when (event) {
    is SDLEvent.Window -> event.windowId
    is SDLEvent.Key -> event.windowId
    is SDLEvent.TextInput -> event.windowId
    is SDLEvent.MouseMotion -> event.windowId
    is SDLEvent.MouseButton -> event.windowId
    is SDLEvent.MouseWheel -> event.windowId
    is SDLEvent.Drop -> event.windowId
    else -> null
}