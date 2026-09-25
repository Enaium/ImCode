package cn.enaium.imcode.config

import kotlinx.serialization.Serializable

/** A file the user opened before (startup page history). */
@Serializable
data class RecentFile(
    val path: String,
    val lastOpened: Long = 0L,
)

/** Persistent state of one workspace window. */
@Serializable
data class WorkspaceEntry(
    val dir: String,
    val openFiles: List<String> = emptyList(),
    val activeFile: String? = null,
    val x: Float = -1f,
    val y: Float = -1f,
    val w: Float = -1f,
    val h: Float = -1f,
    val explorerWidth: Float = 250f,
    val outputHeight: Float = 280f,
)

/**
 * One language server configuration. [patterns] are file-name globs
 * (`*.kt`); when any open document matches, a server instance for this entry
 * is started (lsp4ij-style lifecycle). [command] is the launch command line.
 */
@Serializable
data class LspServer(
    val name: String = "kotlin",
    val patterns: List<String> = listOf("*.kt", "*.kts"),
    val command: List<String> = listOf("kotlin-lsp", "--stdio"),
    /**
     * The command line exactly as the user typed it in settings, quotes
     * preserved. When set, it is what the edit box shows and what gets
     * re-split for launching; [command] stays in sync (split result) for
     * compatibility with older configs and other readers.
     */
    val commandString: String? = null,
    /** languageId sent in didOpen; null means "derive from file extension". */
    val languageId: String? = null,
    val env: Map<String, String> = emptyMap(),
)

/** Editor widget preferences. */
@Serializable
data class EditorConfig(
    val tabSize: Int = 4,
    val insertSpaces: Boolean = true,
    val wordWrap: Boolean = false,
    val showLineNumbers: Boolean = true,
    val showMinimap: Boolean = false,
    val autoIndent: Boolean = true,
    val lineFolding: Boolean = true,
    val lineSpacing: Float = 1f,
    /**
     * JetBrains theme name from `JetBrainsThemes.all` (lsp-edit); empty keeps
     * lsp-edit's built-in Dark palette.
     */
    val theme: String = "",
)

/** A debug adapter that debugs the files matching [patterns]. */
@Serializable
data class DebugAdapterConfig(
    val name: String = "python",
    /** File patterns this adapter handles, e.g. `*.py`. */
    val patterns: List<String> = listOf("*.py"),
    /** Command line, e.g. `python3 -m debugpy.adapter`. */
    val command: List<String> = listOf("python3", "-m", "debugpy.adapter"),
    /** The command line as typed (quotes preserved); see [LspServer.commandString]. */
    val commandString: String? = null,
    /**
     * The `launch` request arguments as JSON, with `$FILE` and `$DIR`
     * substituted when a session starts — e.g.
     * `{"type":"python","request":"launch","program":"$FILE","console":"internalConsole"}`.
     */
    val launchArgs: String = "",
    val env: Map<String, String> = emptyMap(),
)

/** What opening a folder does while a window is already open. */
object OpenFolderPolicy {
    /** Always a new window. */
    const val NewWindow = "newWindow"

    /** Reuse the window that has the focus. */
    const val CurrentWindow = "currentWindow"

    /** Ask which of the two, every time. */
    const val Ask = "ask"

    val ALL = listOf(NewWindow, CurrentWindow, Ask)

    fun label(policy: String): String = when (policy) {
        NewWindow -> "New window"
        CurrentWindow -> "Current window"
        else -> "Ask every time"
    }
}

/** Application configuration, persisted as JSON in `~/.imcode/config.json`. */
@Serializable
data class Config(
    val fontSize: Float = 13f,
    /**
     * TTF/OTF/TTC used for all UI text; empty keeps ImGui's built-in font.
     * Read when a window builds its font atlas, so a change applies after
     * ImCode is restarted.
     */
    val mainFontPath: String = "",
    /**
     * TTF/OTF/TTC merged into the main font so glyphs the main font lacks
     * (CJK, symbols) render instead of tofu boxes; empty merges nothing.
     * Applies after a restart, like [mainFontPath].
     */
    val fallbackFontPath: String = "",
    /** Log every LSP RPC frame into the "LSP" output tab. */
    val rpcLogging: Boolean = true,
    /** Reopen the previous session's workspaces on startup; off = show the project list page. */
    val restoreWorkspacesOnStart: Boolean = false,
    /** action id -> key chord ("Ctrl+Shift+F"). Empty entries fall back to IntelliJ defaults. */
    val shortcuts: Map<String, String> = emptyMap(),
    val recentFiles: List<RecentFile> = emptyList(),
    val workspaces: List<WorkspaceEntry> = emptyList(),
    val lspServers: List<LspServer> = emptyList(),
    /** Debug adapters, matched to a file the same way language servers are. */
    val debugAdapters: List<DebugAdapterConfig> = emptyList(),
    /** Where "Open Folder" puts the folder (see [OpenFolderPolicy]). */
    val openFolderPolicy: String = OpenFolderPolicy.Ask,
    /**
     * Folders opened before, newest first. Unlike `workspaces` (the windows
     * that happen to be open) this is a history and survives restarts.
     */
    val recentFolders: List<RecentFile> = emptyList(),
    val editor: EditorConfig = EditorConfig(),
) {
    fun effectiveShortcuts(): Map<String, String> = DefaultShortcuts.IDEA + shortcuts
}