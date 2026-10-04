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
import cn.enaium.sdl.SDLWindowFlags

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
    var quickFixProbe: Boolean = false
    var saveProbe: Boolean = false

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
                "--quickfix-probe" -> quickFixProbe = true
                "--save-probe" -> saveProbe = true
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

    // macOS: without this hint a click on a window that is not the key window
    // only activates the app — the click itself is swallowed, so the first
    // click on a just-opened window does nothing and the second one is what
    // "works". An editor wants the opposite: the caret lands where the user
    // clicked, on the first try. SDL answers Cocoa's acceptsFirstMouse: from
    // this hint, so it must be set before any window is created.
    SDL.setHint("SDL_MOUSE_FOCUS_CLICKTHROUGH", "1")
    SDL.setHint("SDL_MAC_MOUSE_FOCUS_CLICKTHROUGH", "1")
    // Activating on show/raise is the default; set it explicitly because the
    // startup raise below relies on it to bring the app forward.
    SDL.setHint("SDL_WINDOW_ACTIVATE_WHEN_SHOWN", "1")
    SDL.setHint("SDL_WINDOW_ACTIVATE_WHEN_RAISED", "1")

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
    // "Open Folder" with the current-window policy: the focused window swaps
    // its workspace in place (the hook is here because this is where the
    // windows live).
    core.onAdoptWorkspaceInCurrentWindow = { ws ->
        val target = windows.firstOrNull { it.isPrimary } ?: windows.firstOrNull()
        if (target == null) {
            false
        } else {
            target.adopt(ws)
            true
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
    // Startup activation: SDL asks macOS to activate the app when the window is
    // shown, but ImCode then spends a second or more building the font atlas
    // and the first frame before it polls anything — an activation request that
    // is not serviced in time is dropped, and the window opens behind every
    // other one (its first click then being the activation click that macOS
    // swallows). Re-assert it while the event loop is actually pumping, and
    // stop for good once a window has been focused: after startup, focus is the
    // user's business, not ours.
    var startupFocusSettled = false
    var qfStage = 0
    var spStage = 0
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
                    // The console pane scrolls its own scrollback (or reports
                    // the wheel to a full-screen application) while the pointer
                    // is over it; elsewhere the wheel belongs to the UI.
                    if (event.y != 0f) ctx.workspace?.terminal?.wheel(event.y)
                    // Horizontal wheel: the scrollbar must move OPPOSITE to the
                    // trackpad swipe so the content follows the finger.
                    if (event.x != 0f) {
                        // Applied in the window the event belongs to: plain
                        // ImGui.getIO() writes into whichever context happens
                        // to be current, i.e. into another window's scroll.
                        ctx.withContext {
                            cn.enaium.imgui.ImGui.getIO().addMouseWheelEvent(-2f * event.x, 0f)
                        }
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
                        // the imgui IO queue) — or into the console pane while
                        // it owns the keyboard.
                        val ws = ctx?.workspace
                        if (ws != null && ws.terminal.wantsTextInput) {
                            ws.terminal.textInput(event.text)
                        } else if (ws != null && !core.hostFieldFocused && !ws.newFilePromptOpen) {
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
                    val target = ctx ?: primary
                    if (event is SDLEvent.MouseButton) {
                        // imgui applies a click at the position it knows when
                        // the button event is processed, and pushes a position
                        // queued after a button change to the NEXT frame. The
                        // SDL backend queues the position last, so a click that
                        // arrives without a preceding motion event — the first
                        // click after the pointer entered a window that was not
                        // focused, which is exactly the click that also
                        // activates the app — is applied at the stale position
                        // and lands on nothing; the click after it is the one
                        // that "works". Feed the position first, the order
                        // imgui's own SDL backend uses.
                        target.imgui.processEvent(
                            SDLEvent.MouseMotion(event.timestamp, event.windowId, event.x, event.y, 0f, 0f),
                        )
                    }
                    target.imgui.processEvent(event)
                }
            }
        }

        // Re-assert startup activation while events are being pumped (see the
        // note on startupFocusSettled). Bounded: give up after ~2 seconds so a
        // launch the user left in the background never keeps stealing focus.
        if (!startupFocusSettled) {
            val focused = windows.any { (it.window.flags and SDLWindowFlags.INPUT_FOCUS) != 0uL }
            if (focused || frame >= 200) {
                startupFocusSettled = true
            } else if (frame % 30 == 0) {
                for (w in windows.toList()) w.window.raise()
            }
        }

        mailbox.drain()

        // --save-probe: type into the document, inject a real Cmd/Ctrl+S and
        // report whether the file on disk changed.
        if (cli.saveProbe) {
            val doc = core.activeDoc()
            when (spStage) {
                0 -> if (frame > 700 && doc != null) {
                    doc.editor.setCursor(cn.enaium.lsp.edit.DocPos(0, 0))
                    doc.editor.queueTextInput("// probe\n")
                    println("SP-PROBE typed, dirty=${doc.dirty}")
                    spStage = 1
                }
                1 -> if (frame > 730 && doc != null) {
                    println("SP-PROBE before save: dirty=${doc.dirty}")
                    cn.enaium.imgui.ImGui.getIO().addKeyEvent(cn.enaium.imgui.ImGuiKey.LEFT_SUPER, true)
                    cn.enaium.imgui.ImGui.getIO().addKeyEvent(cn.enaium.imgui.ImGuiKey.S, true)
                    spStage = 2
                }
                2 -> if (frame > 750) {
                    cn.enaium.imgui.ImGui.getIO().addKeyEvent(cn.enaium.imgui.ImGuiKey.S, false)
                    cn.enaium.imgui.ImGui.getIO().addKeyEvent(cn.enaium.imgui.ImGuiKey.LEFT_SUPER, false)
                    spStage = 3
                }
                3 -> if (frame > 900 && doc != null) {
                    val onDisk = cn.enaium.imcode.platform.ioFile(doc.path).readText()
                    println(
                        "SP-PROBE after save: dirty=${doc.dirty} diskHasProbe=${onDisk.contains("// probe")}",
                    )
                    spStage = 4
                }
            }
        }

        // --quickfix-probe: inject a real Alt+Enter over the caret and report
        // whether the editor's code-action popup opened.
        if (cli.quickFixProbe) {
            val doc = core.activeDoc()
            when (qfStage) {
                0 -> if (frame > 700 && doc?.lspEditor != null) {
                    doc.editor.setCursor(cn.enaium.lsp.edit.DocPos(12, 25))
                    println("QF-PROBE injecting Alt+Enter (bound=true)")
                    cn.enaium.imgui.ImGui.getIO().addKeyEvent(cn.enaium.imgui.ImGuiKey.LEFT_ALT, true)
                    cn.enaium.imgui.ImGui.getIO().addKeyEvent(cn.enaium.imgui.ImGuiKey.ENTER, true)
                    qfStage = 1
                }
                1 -> if (frame > 720) {
                    cn.enaium.imgui.ImGui.getIO().addKeyEvent(cn.enaium.imgui.ImGuiKey.ENTER, false)
                    cn.enaium.imgui.ImGui.getIO().addKeyEvent(cn.enaium.imgui.ImGuiKey.LEFT_ALT, false)
                    qfStage = 2
                }
                2 -> if (frame > 900) {
                    val ed = core.activeDoc()?.lspEditor
                    println(
                        "QF-PROBE result: active=${ed?.codeActionsActive} rect=${ed?.lastCodeActionPopupRect} " +
                            "line12=[${core.activeDoc()?.editor?.buffer?.line(12)}]",
                    )
                    // Pick the first action with a real Enter press.
                    println("QF-PROBE selecting first action with Enter")
                    cn.enaium.imgui.ImGui.getIO().addKeyEvent(cn.enaium.imgui.ImGuiKey.ENTER, true)
                    qfStage = 3
                }
                3 -> if (frame > 920) {
                    cn.enaium.imgui.ImGui.getIO().addKeyEvent(cn.enaium.imgui.ImGuiKey.ENTER, false)
                    qfStage = 4
                }
                4 -> if (frame > 1300) {
                    val doc = core.activeDoc()
                    println(
                        "QF-PROBE applied: line12=[${doc?.editor?.buffer?.line(12)}] " +
                            "active=${doc?.lspEditor?.codeActionsActive}",
                    )
                    qfStage = 5
                }
                5 -> if (frame > 2000) {
                    val doc = core.activeDoc()
                    println(
                        "QF-PROBE diagnostics: count=${doc?.diagnostics?.size} " +
                            "markers=${doc?.editor?.markers?.size} warnings=${doc?.warningCount} " +
                            "detail=${doc?.diagnostics?.map { it.range.start.line }}",
                    )
                    qfStage = 6
                }
            }
        }

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
        // Debug state arrives on the session's coroutines; apply it before the
        // windows draw the toolbar and the editor's execution line.
        core.debug.drain()
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