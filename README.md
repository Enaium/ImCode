# ImCode

![](https://img.cdn1.vip/i/6ab65a8494295_1790335620.webp)

An IDE built on Dear ImGui. Kotlin Multiplatform, rendered through
[imgui-kmp](https://github.com/Enaium/imgui-kmp) and
[sdl-kmp](https://github.com/Enaium/sdl-kmp), editing on
[lsp-edit](https://github.com/Enaium/imgui-lsp-edit) (tree-sitter + semantic
tokens), speaking to language servers through
[lsp-kmp](https://github.com/Enaium/lsp-kmp), with grammars from
[tree-sitter-languages-kmp](https://github.com/Enaium/tree-sitter-languages-kmp).

## Features

### Workspaces and windows

- One SDL window per workspace, plus a primary window that hosts the menu bar,
  welcome screen and dialogs.
- Welcome screen with the remembered project list (reopen last session is
  optional), recent files, multi-window support.
- File explorer: single click selects, double click opens, folders toggle with
  a double click or the arrow; reveal-file action. Folders and files carry
  IntelliJ file-type icons (`FileIcons`, xicons).
- Tabs with per-tab close, "Close Others / Left / Right / Unmodified", middle
  click, unsaved-change prompts; each tab shows its file-type icon.
- Output panel (one tab per language server plus the app log) and a status bar
  with caret position, modified state, diagnostic counts, language, live
  work-done progress and the server status.

### Debugging

- Debug toolbar above the editor (VS Code layout): continue/pause, step over,
  step into, step out, restart, stop — each with the IntelliJ run icon, plus
  the adapter name and where the program stopped.
- Start with F5 (Run menu), pause F6, step F10/F11/Shift+F11, restart
  Ctrl+Shift+F5, stop Shift+F5; breakpoints come from the gutter (click the
  line-number strip, or F9) and are sent to the adapter before the debuggee
  starts.
- The gutter shows the adapter's verdict: verified, rejected, disabled and
  logpoint breakpoints each draw their own IntelliJ icon.
- Adapters are matched to files the same way language servers are
  (`debugAdapters` in the config); the Python one is debugpy over stdio.

### Editor (from lsp-edit)

- Highlighting owned by the language server (semantic tokens) with tree-sitter
  as the fallback, each with per-language queries.
- Folding: boxed `+`/`-` gutter markers, click the `...` to unfold, hover it for
  a resizable, syntax-colored preview of the hidden lines.
- Completion: server-resolved `labelDetails` (parameters/source inline, type
  right-aligned), an IntelliJ icon per item kind, automatic imports through
  `completionItem/resolve`, a documentation side panel, one-undo-step
  acceptance, and a live resolve log.
- Hover with markdown and highlighted code blocks, signature help, inlay hints,
  code lenses, go-to-definition, references, rename.
- 46 JetBrains IntelliJ color themes (`EditorPalette` slots), selectable in
  Settings.
- Scroll-follow that never fights manual scrolling; wrap, minimap, line
  numbers are per-editor preferences.

### Language servers (LSP)

- Lifecycle per workspace: `initialize`/`initialized`, `didOpen` / `didChange`
  (incremental) / `didSave` / `didClose`, restart-from-settings.
- Navigation: definition, references, rename (F2; the dialog closes on Esc or
  when the caret leaves the symbol), workspace symbols, document symbols in the
  structure pane, folding ranges.
- Intelligence: completion (+resolve for detail/documentation/auto-imports),
  hover, signature help, code actions (context menu; `codeAction/resolve`
  supported), inlay hints (+resolve), semantic tokens, code lenses (+resolve),
  diagnostics.
- Protocol plumbing: `workspace/configuration` answers, `window/logMessage`,
  `window/showMessage`, `$/progress` and `window/workDoneProgress/create`
  (progress bar in the status bar), JetBrains `intellij/importLog` surfaced in
  the output panel, `workspace/applyEdit` and `workspace/executeCommand`.

### Navigation and files

- Go to definition works for targets outside every workspace root: they open as
  a tab in the active workspace instead of spawning a project window.
- Entries inside archives (`/lib.jar!/pkg/File.kt`, `jar:file://…`) open with
  the real file content and are read-only — the editor never writes into a jar.
- Find in files (file names or content), workspace symbol search.
- Binary files open as a read-only hex view.

## Getting started

Requirements:

- JDK 25 (Gradle toolchain; the produced bytecode targets JVM 21).
- Every dependency — including the `cn.enaium.imgui:lsp-edit` editor widget and
  the tree-sitter grammars — resolves from Maven Central; no local publishing
  or composite build is required.
- A language server on `PATH` — the default configuration starts
  [`kotlin-lsp`](https://github.com/Kotlin/kotlin-lsp) with `--stdio` for
  Kotlin projects; other servers are configured under Settings → LSP Servers
  (name, file patterns, command line).

```bash
./gradlew jvmRun     # build and run
./gradlew jvmTest    # run the test suite
```

Command-line flags (headless/CI friendly):

| Flag | Effect |
| --- | --- |
| `--open <path>` | Open a file (or folder) right after startup |
| `--settings` | Open the settings window |
| `--frames <n>` | Exit after `n` frames |
| `--seconds <n>` | Exit after `n` seconds |
| `--rpc-log [bool]` | Log every LSP RPC frame into the output panel |
| `--lsp-log` | Dump the output log on exit |
| `--type-test` | Synthesize typing (completion probes) |
| `--fold-test` | Toggle a fold once the first file is open |
| `--no-completion-ui` | Disable the completion popup (behavior probes) |
| `--no-editor-render` | Skip editor painting (headless probes) |
| `--auto-debug` | Completion diagnostics + RPC frames in the output log |

## Configuration

Everything persists as JSON in `~/.imcode/config.json`:

- `lspServers` — name, file patterns (`*.kt, *.kts`), command line;
- `editor` — tab size, spaces-vs-tabs, word wrap, line numbers, minimap,
  theme name;
- `debugAdapters` — name, file patterns and command line (e.g.
  `python3 -m debugpy.adapter`) plus the `launch` arguments as JSON, where
  `$FILE` and `$DIR` are substituted per session;
- `fontSize`, `mainFontPath`, `fallbackFontPath` — fonts for the whole UI
  (Settings → General → Fonts). The fallback TTF/OTF/TTC is merged into the
  main font for the glyphs it lacks (CJK, symbols). Size follows immediately;
  the font files are read when a window builds its font atlas, so a changed
  path applies after a restart. Paths are validated in the dialog — ImGui
  cannot report a font file that failed to load, so a typo would otherwise
  stay silent;
- `shortcuts` — per-action key chords; unset actions fall back to IntelliJ
  defaults (rebindable under Settings → Keymap, which also lists every action
  with its effective chord);
- `recentFiles`, `workspaces` (open tabs and active file per project),
  `restoreWorkspacesOnStart`.

### Default shortcuts (IntelliJ IDEA style)

| Action | Chord |
| --- | --- |
| New File | `Ctrl+Alt+Insert` |
| Open File… | `Ctrl+O` |
| Open Folder… | `Ctrl+Shift+O` |
| Save / Save All | `Ctrl+S` / `Ctrl+Shift+S` |
| Close Tab | `Ctrl+Shift+F4` |
| Find in Files… | `Ctrl+Shift+F` |
| Recent Files | `Ctrl+E` |
| Settings | `Ctrl+Alt+S` |
| Font size | `Ctrl+=` / `Ctrl+-` / `Ctrl+0` |
| Go to Definition / Find References / Rename | `F12` / `Shift+F12` / `F2` |
| Show Context Actions (quick fixes) | `Alt+Enter` |

`Ctrl` is the primary modifier (⌘ on macOS, exactly like IntelliJ keymaps).

## Source map

| Path | Contents |
| --- | --- |
| `Main.kt` | Entry point, CLI parsing, SDL frame loop |
| `app/` | `AppCore` (state/config/workspaces/dialogs), `WindowCtx` (per-window rendering and platform text input), `Mailbox`, `OutputLog` |
| `lsp/` | `LspManager` (server lifecycle, request routing), `LspServerClient` (JSON-RPC, requests and notifications) |
| `editor/` | `Document`, `DocumentManager` (open/save, editor wiring, completion, workspace edits) |
| `ui/` | `WorkspaceWindow` (explorer/tabs/editor/structure/output), `SearchWindow`, `SymbolSearchWindow`, `SettingsWindow`, `MenuBar`, `HubView`/`WelcomeView`, `OutputPanel`, `FileDialogs` |
| `config/` | `Config` (persisted model), `Keymap` (chords, defaults) |
| `util/` | `Uri`, `Glob`, `ShellWords`, `LanguageDetect`, `SearchService` |
| `platform/` | `expect`/`actual` file, process and platform access |

## Related projects

- [imgui-kmp](https://github.com/Enaium/imgui-kmp) — Dear ImGui bindings
- [sdl-kmp](https://github.com/Enaium/sdl-kmp) — SDL3 bindings
- [lsp-kmp](https://github.com/Enaium/lsp-kmp) — LSP/DAP protocol models and JSON-RPC
- [lsp-edit](https://github.com/Enaium/imgui-lsp-edit) — the editor widget ImCode renders
- [tree-sitter-languages-kmp](https://github.com/Enaium/tree-sitter-languages-kmp) — grammar bindings

## License

[MIT](LICENSE) © 2026 Enaium
