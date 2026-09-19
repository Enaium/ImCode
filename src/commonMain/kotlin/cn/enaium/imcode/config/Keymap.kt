package cn.enaium.imcode.config

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiKey
import cn.enaium.imcode.platform.Platform

/** Identifiers for actions that can be bound to a key chord. */
object KeyAction {
    const val NEW_FILE = "newFile"
    const val OPEN_FILE = "openFile"
    const val OPEN_FOLDER = "openFolder"
    const val SAVE = "save"
    const val SAVE_ALL = "saveAll"
    const val CLOSE_TAB = "closeTab"
    const val FIND_FILES = "findFiles"
    const val RECENT_FILES = "recentFiles"
    const val SETTINGS = "settings"
    const val FONT_UP = "fontUp"
    const val FONT_DOWN = "fontDown"
    const val FONT_RESET = "fontReset"
    const val NEW_WORKSPACE = "newWorkspace"
    const val WELCOME = "welcome"
    const val QUICK_FIX = "quickFix"

    val ALL = listOf(
        NEW_FILE, OPEN_FILE, OPEN_FOLDER, SAVE, SAVE_ALL, CLOSE_TAB, FIND_FILES,
        RECENT_FILES, SETTINGS, FONT_UP, FONT_DOWN, FONT_RESET, NEW_WORKSPACE, WELCOME,
        QUICK_FIX,
    )

    fun label(action: String): String = when (action) {
        NEW_FILE -> "New File"
        OPEN_FILE -> "Open File..."
        OPEN_FOLDER -> "Open Folder..."
        SAVE -> "Save"
        SAVE_ALL -> "Save All"
        CLOSE_TAB -> "Close Tab"
        FIND_FILES -> "Find in Files..."
        RECENT_FILES -> "Recent Files"
        SETTINGS -> "Settings"
        FONT_UP -> "Increase Font Size"
        FONT_DOWN -> "Decrease Font Size"
        FONT_RESET -> "Reset Font Size"
        NEW_WORKSPACE -> "New Workspace Window"
        WELCOME -> "Welcome Screen"
        QUICK_FIX -> "Show Context Actions"
        else -> action
    }
}

/** IntelliJ IDEA style defaults (used when an action has no custom binding). */
object DefaultShortcuts {
    val IDEA: Map<String, String> = mapOf(
        KeyAction.NEW_FILE to "Ctrl+Alt+Insert",
        KeyAction.OPEN_FILE to "Ctrl+O",
        KeyAction.OPEN_FOLDER to "Ctrl+Shift+O",
        KeyAction.SAVE to "Ctrl+S",
        KeyAction.SAVE_ALL to "Ctrl+Shift+S",
        KeyAction.CLOSE_TAB to "Ctrl+Shift+F4",
        KeyAction.FIND_FILES to "Ctrl+Shift+F",
        KeyAction.RECENT_FILES to "Ctrl+E",
        KeyAction.SETTINGS to "Ctrl+Alt+S",
        KeyAction.FONT_UP to "Ctrl+=",
        KeyAction.FONT_DOWN to "Ctrl+-",
        KeyAction.FONT_RESET to "Ctrl+0",
        KeyAction.QUICK_FIX to "Alt+Enter",
    )
}

/**
 * A parsed key chord ("Ctrl+Shift+F4"). "Ctrl" binds to the primary modifier
 * (Cmd on macOS, matching Dear ImGui's Ctrl/Super reversal): on Apple
 * hardware "Ctrl+S" means Cmd+S, exactly like IntelliJ keymaps.
 */
data class Chord(val mods: Set<String>, val key: String) {
    fun display(): String {
        val parts = ArrayList<String>()
        // On macOS "Ctrl" means the Command key (IntelliJ layout), so show
        // it as Cmd in menus; Alt maps to Option.
        if ("Ctrl" in mods) parts.add(if (Platform.isMac) "Cmd" else "Ctrl")
        if ("Alt" in mods) parts.add(if (Platform.isMac) "Option" else "Alt")
        if ("Shift" in mods) parts.add("Shift")
        if ("Super" in mods) parts.add(if (Platform.isMac) "Cmd" else "Super")
        parts.add(key)
        return parts.joinToString("+")
    }
}

object Keymap {
    private val keyNames: Map<String, Int> = HashMap<String, Int>().apply {
        ImGuiKey::class.java.declaredFields.forEach { f ->
            if (java.lang.reflect.Modifier.isStatic(f.modifiers) && f.type == Int::class.javaPrimitiveType) {
                val raw = f.name
                val value = f.getInt(null)
                put(raw, value)
                // KEY_0..KEY_9 alias to "0".."9"
                if (raw.startsWith("KEY_") && raw.length == 5) put(raw.removePrefix("KEY_"), value)
            }
        }
        // readable aliases for chord strings
        put("Space", ImGuiKey.SPACE)
        put("Enter", ImGuiKey.ENTER)
        put("Tab", ImGuiKey.TAB)
        put("Escape", ImGuiKey.ESCAPE)
        put("Esc", ImGuiKey.ESCAPE)
        put("Backspace", ImGuiKey.BACKSPACE)
        put("Delete", ImGuiKey.DELETE)
        put("Home", ImGuiKey.HOME)
        put("End", ImGuiKey.END)
        put("PageUp", ImGuiKey.PAGE_UP)
        put("PageDown", ImGuiKey.PAGE_DOWN)
        put("Insert", ImGuiKey.INSERT)
        put("Up", ImGuiKey.UP_ARROW)
        put("Down", ImGuiKey.DOWN_ARROW)
        put("Left", ImGuiKey.LEFT_ARROW)
        put("Right", ImGuiKey.RIGHT_ARROW)
        put("OemPlus", ImGuiKey.EQUAL)
        put("OemMinus", ImGuiKey.MINUS)
        put("OemPeriod", ImGuiKey.PERIOD)
        put("OemComma", ImGuiKey.COMMA)
        put("=", ImGuiKey.EQUAL)
        put("-", ImGuiKey.MINUS)
        put("+", ImGuiKey.EQUAL)
        put(",", ImGuiKey.COMMA)
        put(".", ImGuiKey.PERIOD)
        put("/", ImGuiKey.SLASH)
        put("\\", ImGuiKey.BACKSLASH)
        put(";", ImGuiKey.SEMICOLON)
        put("'", ImGuiKey.APOSTROPHE)
        put("`", ImGuiKey.GRAVE_ACCENT)
        put("[", ImGuiKey.LEFT_BRACKET)
        put("]", ImGuiKey.RIGHT_BRACKET)
    }

    /** Parses "Ctrl+Shift+F4"-style text; returns null on unknown tokens. */
    fun parse(text: String): Chord? {
        if (text.isBlank()) return null
        val parts = text.trim().split('+').map { it.trim() }.filter { it.isNotEmpty() }
        val mods = HashSet<String>()
        val keys = ArrayList<String>()
        for (p in parts) {
            when (p.lowercase()) {
                "ctrl", "cmd", "command", "meta" -> mods.add("Ctrl")
                "alt", "option" -> mods.add("Alt")
                "shift" -> mods.add("Shift")
                "super", "win", "windows" -> mods.add("Super")
                else -> keys.add(p)
            }
        }
        if (keys.size != 1) return null
        val key = keys[0]
        if (!keyNames.containsKey(key)) return null
        return Chord(mods, key)
    }

    fun keyCode(chord: Chord): Int = keyNames[chord.key] ?: 0

    /** (name, keyCode) pairs of every known key, for capture UIs. */
    fun allKeyNames(): List<Pair<String, Int>> = keyNames.map { it.key to it.value }

    private fun modDown(mod: String): Boolean = when (mod) {
        "Ctrl" -> {
            if (Platform.isMac) ImGui.isKeyDown(ImGuiKey.LEFT_SUPER) || ImGui.isKeyDown(ImGuiKey.RIGHT_SUPER)
            else ImGui.isKeyDown(ImGuiKey.LEFT_CTRL) || ImGui.isKeyDown(ImGuiKey.RIGHT_CTRL)
        }
        "Alt" -> ImGui.isKeyDown(ImGuiKey.LEFT_ALT) || ImGui.isKeyDown(ImGuiKey.RIGHT_ALT)
        "Shift" -> ImGui.isKeyDown(ImGuiKey.LEFT_SHIFT) || ImGui.isKeyDown(ImGuiKey.RIGHT_SHIFT)
        "Super" -> ImGui.isKeyDown(ImGuiKey.LEFT_SUPER) || ImGui.isKeyDown(ImGuiKey.RIGHT_SUPER)
        else -> false
    }

    /** True exactly on the frame [chord] was pressed (key down event, no repeat). */
    fun pressed(chord: Chord): Boolean {
        val code = keyCode(chord)
        if (code == 0) return false
        if (!ImGui.isKeyPressed(code, repeat = false)) return false
        // every configured modifier must be down...
        return chord.mods.all { modDown(it) } &&
            // ...and every *other* modifier must be up (Ctrl+Z must not match Ctrl+Shift+Z)
            !modDownUnlisted(chord)
    }

    private fun modDownUnlisted(chord: Chord): Boolean {
        val candidates = listOf("Ctrl", "Alt", "Shift", "Super")
        // On macOS "Ctrl" binds to the Command key (modDown("Ctrl") checks
        // LEFT_SUPER/RIGHT_SUPER), so "Super" is the same physical key:
        // excluding it from the unlisted check prevents Ctrl+Z from being
        // rejected because Cmd (Super) is down.
        return candidates
            .filter { it !in chord.mods }
            .filter { !(Platform.isMac && it == "Super") }
            .any { modDown(it) }
    }
}