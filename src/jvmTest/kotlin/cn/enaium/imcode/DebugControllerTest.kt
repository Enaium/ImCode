package cn.enaium.imcode

import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImTextureID
import cn.enaium.imgui.ImVec2
import cn.enaium.imcode.app.AppCore
import cn.enaium.imcode.app.Mailbox
import cn.enaium.imcode.config.DebugAdapterConfig
import cn.enaium.imcode.editor.Document
import cn.enaium.lsp.edit.Editor
import cn.enaium.lsp.edit.Language
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The IDE's own debug path, end to end with a real adapter: the configured
 * adapter is matched to the file, spawned, driven through a session, and its
 * verdicts land back in the editor's gutter — the chain F5 runs.
 */
class DebugControllerTest {

    private val script = """
        def add(a, b):
            total = a + b
            return total


        print(add(1, 2))
    """.trimIndent() + "\n"

    @Test
    fun controllerDebugsAFileAndSyncsBreakpoints() {
        val python = debugpyPython()
        if (python == null) {
            println("SKIP: no python with debugpy found (set IMCODE_DEBUGPY_PYTHON to point at one)")
            return
        }
        val (dir, file) = tempPythonScript("demo.py", script)
        val breakpointLine = script.lines().indexOfFirst { it.contains("total = a + b") } + 1

        val ctx = ImGui.createContext()
        try {
            val io = ImGui.getIO()
            io.displaySize = ImVec2(800f, 600f)
            io.deltaTime = 1f / 60f
            io.fonts.addFontDefault(ImFontConfig(sizePixels = 13f))
            check(io.fonts.build())
            io.fonts.setTexID(ImTextureID(0uL))

            val core = AppCore(Mailbox())
            core.config = core.config.copy(
                debugAdapters = listOf(
                    DebugAdapterConfig(
                        name = "python",
                        patterns = listOf("*.py"),
                        command = listOf(python, "-m", "debugpy.adapter"),
                        launchArgs = """{"type":"python","request":"launch","program":"${'$'}FILE","console":"internalConsole"}""",
                    ),
                ),
            )
            val editor = Editor(initialText = script, language = Language.python)
            val doc = Document(
                path = file.absolutePath,
                workspaceDir = dir.absolutePath,
                uri = "file://${file.absolutePath}",
                languageId = "python",
                editor = editor,
            )
            // The gutter sets a breakpoint; the adapter is the judge.
            editor.setBreakpoints(setOf(breakpointLine))

            assertTrue(core.debug.canDebug(doc), "the configured adapter must match the file")
            core.debug.start(doc)

            // Drive the frame loop's drain until the program stops.
            runBlocking {
                withTimeout(60_000) {
                    while (core.debug.session?.stackFrames.isNullOrEmpty()) {
                        core.debug.drain()
                        delay(20)
                    }
                }
            }
            val session = assertNotNull(core.debug.session)
            assertEquals("breakpoint", session.stopReason)
            assertEquals(breakpointLine, session.stackFrames.first().line)
            assertEquals(breakpointLine, editor.getExecutionLine(), "the editor follows the stop (1-based)")

            // The adapter's verdict reaches the gutter: the marker is verified.
            val marker = editor.breakpointAt(breakpointLine - 1)
            assertEquals(true, marker?.verified, "the adapter verified the breakpoint")

            // Step over through the toolbar's action.
            core.debug.stepOver()
            runBlocking {
                withTimeout(60_000) {
                    val target = breakpointLine + 1
                    while (session.stackFrames.firstOrNull()?.line != target) {
                        core.debug.drain()
                        delay(20)
                    }
                }
            }
            assertEquals(breakpointLine + 1, session.stackFrames.first().line)

            // Stopping ends the session and clears the verdict from the gutter.
            core.debug.stop()
            runBlocking {
                withTimeout(10_000) {
                    while (core.debug.active) delay(20)
                }
            }
            assertNull(core.debug.session, "stopping clears the session")
            assertNull(
                editor.breakpointAt(breakpointLine - 1)?.verified,
                "with no adapter judging it, the marker goes back to plain",
            )
        } finally {
            ImGui.destroyContext(ctx)
            dir.deleteRecursively()
        }
    }
}
