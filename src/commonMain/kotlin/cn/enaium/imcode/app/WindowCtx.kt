package cn.enaium.imcode.app

import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiConfigFlags
import cn.enaium.imgui.ImGuiPopupFlags
import cn.enaium.imgui.backends.sdl.ImGuiSdlBackend
import cn.enaium.imgui.backends.sdl.ImGuiSdlRendererBackend
import cn.enaium.imcode.ui.HubView
import cn.enaium.imcode.ui.WorkspaceWindow
import cn.enaium.imcode.ui.processWindowShortcuts
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
    val imgui: ImGuiSdlBackend = ImGuiSdlBackend(window)
    private val backend = ImGuiSdlRendererBackend(renderer)
    private val hub: HubView = HubView(core, this)

    /** Highest framebuffer scale; used to keep text crisp on Retina. */
    val density: Float

    /** Set when this window may be torn down after the current frame. */
    @Volatile
    var wantDestroy = false

    /** Window id, matches [cn.enaium.sdl.SDLEvent] windowId fields. */
    val windowId: Int get() = window.id

    val kind: WindowKind get() = if (workspace != null) WindowKind.WORKSPACE else WindowKind.HUB

    init {
        imgui.init()
        density = maxOf(imgui.framebufferScale.x, imgui.framebufferScale.y, 1f)
        val io = ImGui.getIO()
        // window layout is persisted in ~/.imcode/config.json, not imgui.ini
        io.iniFilename = null
        io.configFlags = io.configFlags or ImGuiConfigFlags.NAV_ENABLE_KEYBOARD or ImGuiConfigFlags.DOCKING_ENABLE
        applyFont(core.config.fontSize)
        workspace?.let { adopt(it) }
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

    fun applyFont(sizePx: Float) {
        val fonts = ImGui.getIO().fonts
        fonts.addFontDefault(ImFontConfig(sizePixels = sizePx * density, rasterizerDensity = density))
        check(fonts.build()) { "font atlas build failed" }
        val tex = fonts.getTexDataAsRGBA32()
        val texId = backend.uploadFontTexture(tex.pixels, tex.width, tex.height)
        fonts.setTexID(texId)
    }

    /** Draws one frame for this window. */
    fun renderFrame() {
        if (core.fontDirty) applyFont(core.config.fontSize)
        // ImGui's keyboard navigation must stay off while the editor owns
        // the keyboard: otherwise arrow keys move the nav focus across the
        // tab bar/panes and scroll the view while the user is moving the
        // caret or picking a completion item. The editor/popup read the
        // keys themselves.
        val activeDoc = workspace?.activeFile?.let { core.documents.get(it) }
        val editorOwnsKeys = activeDoc?.editor?.isFocusedStrict == true ||
            activeDoc?.completionActive == true
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

        // window-local shortcuts (skip while popups/dialogs need the keys)
        val fileDialogOpen = core.fileDialogs.isOpen
        val modalOpen = ImGui.isPopupOpen("", ImGuiPopupFlags.ANY_POPUP)
        if (!fileDialogOpen && !modalOpen) {
            processWindowShortcuts(core, workspace)
        }

        val ws = workspace
        if (ws != null) {
            ws.draw(core)
            // primary window alone hosts the auxiliary UI above the workspace
            if (isPrimary) hub.drawAux()
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
        try { backend.close() } catch (_: Throwable) {}
        try { ImGui.destroyContext(context) } catch (_: Throwable) {}
        try { renderer.close() } catch (_: Throwable) {}
        try { window.close() } catch (_: Throwable) {}
    }
}