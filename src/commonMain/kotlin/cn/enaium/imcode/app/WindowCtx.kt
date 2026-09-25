package cn.enaium.imcode.app

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiConfigFlags
import cn.enaium.imgui.backends.sdl.ImGuiSdlBackend
import cn.enaium.imgui.backends.sdl.ImGuiSdlRendererBackend
import cn.enaium.imcode.app.OutputLog
import cn.enaium.imcode.config.Config
import cn.enaium.imcode.ui.HubView
import cn.enaium.imcode.ui.NotificationView
import cn.enaium.imcode.ui.WorkspaceWindow
import cn.enaium.imcode.ui.processWindowShortcuts
import cn.enaium.lsp.edit.EditorFontSettings
import cn.enaium.lsp.edit.installEditorFonts
import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLWindow
import cn.enaium.sdl.SDLWindowFlags

/** What a window currently hosts. */
enum class WindowKind { HUB, WORKSPACE }

/**
 * One SDL window + its own imgui context/renderer.
 *
 * Window 0 starts as the hub (welcome + settings/search/dialogs). The FIRST
 * workspace is adopted into window 0 (it becomes a workspace window, so no
 * blank hub window is left around); every further workspace gets its own
 * window. The primary window keeps drawing the auxiliary UI (settings, search,
 * about, exit-confirm, file dialogs) above its workspace layout.
 */
class WindowCtx(
    val isPrimary: Boolean,
    var workspace: WorkspaceWindow?,
    private val core: AppCore,
    private val width: Int,
    private val height: Int,
) {
    val window: SDLWindow = SDL.createWindow(
        title = "ImCode",
        width = width,
        height = height,
        flags = SDLWindowFlags.RESIZABLE or SDLWindowFlags.HIGH_PIXEL_DENSITY,
    )
    private val renderer = SDL.createRenderer(window)
    private val context = ImGui.createContext()
    val imgui: ImGuiSdlBackend
    private val backend: ImGuiSdlRendererBackend
    private val hub: HubView

    /** Highest framebuffer scale; used to keep text crisp on Retina. */
    val density: Float

    /** Size the atlas was rasterized at; [applyFontScale] scales relative to it. */
    private var bakedFontSizePx = 0f

    /** Set when this window may be torn down after the current frame. */
    @Volatile
    var wantDestroy = false

    /** Window id, matches [cn.enaium.sdl.SDLEvent] windowId fields. */
    val windowId: Int get() = window.id

    val kind: WindowKind get() = if (workspace != null) WindowKind.WORKSPACE else WindowKind.HUB

    init {
        // A context is process-global state and creating one does not make it
        // current (ImGui restores the previous one), so this window must select
        // its own before touching anything. Without this the second window
        // built the *first* window's already-built font atlas — which crashes
        // in ImFontAtlas::Build — and drew into the wrong context.
        //
        // The backends are built only after that, because they capture
        // ImGui.getIO() in their constructor: built earlier they captured the
        // previous window's IO, and the SDL backend's init() then assigned
        // configFlags (a plain assignment of NAV_ENABLE_KEYBOARD) into that
        // other window. That wiped the other window's DOCKING_ENABLE, which
        // makes DockSpace a no-op and leaves the window showing nothing but
        // its menu bar. The context is put back before returning for the same
        // reason: a window must not leave a foreign context current while the
        // caller keeps setting windows up.
        val previous = ImGui.getCurrentContext()
        ImGui.setCurrentContext(context)
        imgui = ImGuiSdlBackend(window)
        backend = ImGuiSdlRendererBackend(renderer)
        hub = HubView(core, this)
        imgui.init()
        density = maxOf(imgui.framebufferScale.x, imgui.framebufferScale.y, 1f)
        val io = ImGui.getIO()
        // window layout is persisted in ~/.imcode/config.json, not imgui.ini
        io.iniFilename = null
        io.configFlags = io.configFlags or ImGuiConfigFlags.NAV_ENABLE_KEYBOARD or ImGuiConfigFlags.DOCKING_ENABLE
        applyFont(core.config)
        workspace?.let { adopt(it) }
        if (previous != null) ImGui.setCurrentContext(previous)
    }

    /** Turns this window into the host of [ws] (updates the title). */
    fun adopt(ws: WorkspaceWindow) {
        workspace = ws
        window.title = ws.title()
    }

    /** Back to hub mode (window 0 after its workspace closed). */
    fun release() {
        workspace = null
        window.title = "ImCode"
    }

    fun applyFont(cfg: Config) {
        val fonts = ImGui.getIO().fonts
        installEditorFonts(
            EditorFontSettings(
                mainFontPath = cfg.mainFontPath.ifBlank { null },
                fallbackFontPath = cfg.fallbackFontPath.ifBlank { null },
                sizePx = cfg.fontSize,
            ),
            density = density,
        )
        check(fonts.build()) { "font atlas build failed" }
        val tex = fonts.getTexDataAsRGBA32()
        val texId = backend.uploadFontTexture(tex.pixels, tex.width, tex.height)
        fonts.setTexID(texId)
        bakedFontSizePx = cfg.fontSize
        OutputLog.info(
            "App",
            "fonts: main=${cfg.mainFontPath.ifBlank { "built-in" }}, " +
                "fallback=${cfg.fallbackFontPath.ifBlank { "none" }}, size=${cfg.fontSize}",
        )
    }

    /**
     * Applies a font-size change by scaling the baked atlas.
     *
     * Re-rasterizing would need a face to join an atlas that is already built
     * (and there is no way to clear one), so the glyphs stay as they were baked
     * until ImCode restarts — which re-bakes them crisply at the new size. The
     * layout follows immediately either way.
     */
    fun applyFontScale(sizePx: Float) {
        if (bakedFontSizePx <= 0f) return
        ImGui.getIO().fontGlobalScale = sizePx / bakedFontSizePx
    }

    /** Runs [body] with this window's ImGui context current. */
    fun withContext(body: () -> Unit) {
        val previous = ImGui.getCurrentContext()
        ImGui.setCurrentContext(context)
        body()
        if (previous != null) ImGui.setCurrentContext(previous)
    }

    /** Draws one frame for this window. */
    fun renderFrame() {
        // Windows render one after another, each with its own context: select
        // it before drawing, or this frame lands in another window's context.
        ImGui.setCurrentContext(context)
        // Anything this frame opens (settings, search, dialogs) belongs to
        // this window, so it is drawn here and not over another project.
        core.currentWindowId = windowId
        if (core.fontDirty) applyFontScale(core.config.fontSize)
        // ImGui's keyboard navigation must stay off while the editor owns
        // the keyboard: otherwise arrow keys move the nav focus across the
        // tab bar/panes and scroll the view while the user is moving the
        // caret or picking a completion item. The editor/popup read the
        // keys themselves.
        val activeDoc = workspace?.activeFile?.let { core.documents.get(it) }
        // The IDE's own text fields (search, settings, symbol search) own the
        // keyboard while they are open: the editor must not act on keys then,
        // otherwise Backspace in a search box also deletes document text.
        val hostFieldFocused = core.hostFieldFocused || (workspace?.newFilePromptOpen == true)
        activeDoc?.editor?.keyboardOwnedByHost = hostFieldFocused
        val editorOwnsKeys = !hostFieldFocused && (activeDoc?.editor?.isFocusedStrict == true ||
            activeDoc?.completionActive == true)
        val io = ImGui.getIO()
        io.configFlags = if (editorOwnsKeys) {
            io.configFlags and ImGuiConfigFlags.NAV_ENABLE_KEYBOARD.inv()
        } else {
            io.configFlags or ImGuiConfigFlags.NAV_ENABLE_KEYBOARD
        }
        imgui.newFrame()

        // synthetic typing for --type-test: start only after the document
        // has opened (>= frame 100), one character every 20 frames so each
        // keystroke triggers its own autocomplete session
        if (core.typeTestRemaining > 0) {
            if (core.frameCount >= 100 && core.typeTestCounter % 20 == 0) {
                core.typeTestRemaining--
                // The lsp-edit Editor reads text via queueTextInput (not the
                // imgui IO queue), so synthetic typing goes straight in.
                val doc = core.activeDoc()
                if (doc != null) doc.editor.queueTextInput(core.typeTestChar.toInt().toChar().toString())
            }
            core.typeTestCounter++
        }

        // Window-local shortcuts. Only the file dialog takes them away: an
        // ANY_POPUP check also matched the editor's own popups (code actions,
        // completion, context menu), which silently disabled Cmd+S and every
        // other shortcut whenever one was open.
        val fileDialogOpen = core.fileDialogs.isOpen
        if (!fileDialogOpen) {
            processWindowShortcuts(core, workspace)
        }

        val ws = workspace
        if (ws != null) {
            ws.draw(core)
            // The floating UI is drawn by every window; drawAux itself skips
            // the ones that did not ask for it, so a dialog opened in one
            // project's window never lands on another project.
            hub.drawAux()
            // Balloons are about the app, not one project, so the primary
            // window is the single place they appear.
            if (isPrimary) NotificationView.drawBalloons(core)
        } else {
            // hub mode: welcome + menu + aux + status bar
            hub.drawPrimary()
        }

        // SDL only emits TextInput events while text input is active, and
        // the lsp-edit Editor never sets the imgui io.wantTextInput flag.
        // Mirror BOTH text consumers on the SDL window: the focused code
        // editor (so letters reach it) and any focused imgui edit box
        // (search/rename/settings — their io.wantTextInput drives the
        // backend's startTextInput). Stopping while an edit box is active
        // killed their typing. Stop only when neither wants text, so the
        // system IME does not stay up and block clicks on the rest of the UI.
        // Strict keyboard focus only: hover must not keep the system IME
        // alive (that blocked clicks on the rest of the UI).
        val editorFocused = ws?.activeFile?.let { core.documents.get(it)?.editor?.isFocusedStrict } == true
        val wantText = io.wantTextInput || editorFocused
        val inputActive = cn.enaium.sdl.SDL.textInputActive(windowId)
        if (wantText && !inputActive) {
            cn.enaium.sdl.SDL.startTextInput(windowId)
        } else if (!wantText && inputActive) {
            cn.enaium.sdl.SDL.stopTextInput(windowId)
        }

        ImGui.render()
        renderer.drawColor = SDLColor(18, 18, 24, 255)
        renderer.clear()
        backend.renderDrawData(ImGui.getDrawData())
        renderer.present()
    }

    /** SDL close button on a workspace window: confirm (unsaved changes) then close. */
    fun requestCloseWorkspace() {
        val ws = workspace ?: return
        if (core.pendingCloseWs == ws) return
        core.initiateWorkspaceClose(ws)
    }

    /** Destroys renderer/imgui/window resources (after the last frame). */
    fun closeRendering() {
        // A dialog this window owned would otherwise stay open with nobody
        // left to draw it; hand it back so the remaining window shows it.
        if (core.uiWindowId == windowId) core.uiWindowId = -1
        try { ImGui.setCurrentContext(context) } catch (_: Throwable) {}
        try { backend.close() } catch (_: Throwable) {}
        // Destroying the current context clears ImGui's current-context slot,
        // so the next window selects its own again.
        try { ImGui.destroyContext(context) } catch (_: Throwable) {}
        try { renderer.close() } catch (_: Throwable) {}
        try { window.close() } catch (_: Throwable) {}
    }
}