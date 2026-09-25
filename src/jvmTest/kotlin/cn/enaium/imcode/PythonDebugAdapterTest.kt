package cn.enaium.imcode

import cn.enaium.imcode.platform.launchRpcProcess
import cn.enaium.lsp.dap.DebugClientLauncher
import cn.enaium.lsp.dap.model.SourceBreakpoint
import cn.enaium.lsp.edit.Editor
import cn.enaium.lsp.edit.Language
import cn.enaium.lsp.edit.dap.DapSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole debug path against a real adapter: a Python file, debugpy over
 * stdio, a breakpoint the adapter verifies, a stop on that line, a step, and
 * the program running to completion.
 *
 * Runs only where a Python with debugpy can be found (see [debugpyPython]);
 * without one there is nothing to debug and the test says so instead of
 * failing on a machine that simply lacks the tooling.
 */
class PythonDebugAdapterTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun debugpyStopsAtABreakpointAndSteps() {
        val python = debugpyPython()
        if (python == null) {
            println("SKIP: no python with debugpy found (set IMCODE_DEBUGPY_PYTHON to point at one)")
            return
        }

        val (dir, script) = tempPythonScript(
            "demo.py",
            """
            def add(a, b):
                total = a + b
                return total


            print(add(1, 2))
            """.trimIndent() + "\n",
        )

        val source = script.readText().lines()
        val breakpointLine = source.indexOfFirst { it.contains("total = a + b") } + 1

        val process = launchRpcProcess(
            listOf(python, "-m", "debugpy.adapter"),
            cwd = dir.absolutePath,
            env = emptyMap(),
            onStderr = { println("[debugpy] $it") },
        )
        val editor = Editor(initialText = script.readText(), language = Language.python)
        val session = DapSession(
            editor = editor,
            client = DebugClientLauncher(process.transport),
            sourceName = "demo.py",
            sourcePath = script.absolutePath,
        )
        try {
            runBlocking {
                // Breakpoints are sent with the launch: configurationDone is
                // what starts the debuggee, so setting them afterwards would
                // arrive once the program has already run past them.
                session.start(
                    breakpoints = listOf(SourceBreakpoint(line = breakpointLine)),
                    launchArgs = json.parseToJsonElement(
                        """
                        {"type":"python","request":"launch","program":"${script.absolutePath}",
                         "console":"internalConsole","justMyCode":false}
                        """.trimIndent(),
                    ),
                )
            }

            // The adapter judges the breakpoint we sent.
            val verdict = session.breakpoints.firstOrNull()
            assertTrue(verdict?.verified == true, "debugpy must verify the breakpoint, got ${session.breakpoints}")

            // Wait for the program to stop on it. The session applies the
            // stack first and fetches the frame's variables right after, so
            // wait for both before asserting on either.
            runBlocking {
                withTimeout(60_000) {
                    while (session.stackFrames.isEmpty() || session.variables.isEmpty()) {
                        session.drain()
                        delay(20)
                    }
                }
            }
            assertEquals("breakpoint", session.stopReason)
            val top = session.stackFrames.first()
            assertEquals(breakpointLine, top.line, "stopped on the breakpoint line")
            assertEquals(script.absolutePath, top.source?.path)
            assertEquals(breakpointLine, editor.getExecutionLine(), "the editor shows the stop line (1-based)")
            assertTrue(session.variables.isNotEmpty(), "locals are available: ${session.variables.map { it.name }}")
            assertTrue(
                session.variables.any { it.name == "a" },
                "the frame's locals include the parameters: ${session.variables.map { it.name }}",
            )

            // Step over: the next stop is the following line.
            runBlocking { session.next() }
            runBlocking {
                withTimeout(60_000) {
                    while (session.stackFrames.isEmpty()) {
                        session.drain()
                        delay(20)
                    }
                }
            }
            assertEquals(
                breakpointLine + 1,
                session.stackFrames.first().line,
                "stepping over moves to the next line",
            )

            // Run to the end: the adapter reports the exit.
            runBlocking { session.continue_() }
            runBlocking {
                withTimeout(60_000) {
                    while (session.active) {
                        session.drain()
                        delay(20)
                    }
                }
            }
            assertEquals(0, session.exitCode, "the program exits cleanly")
        } finally {
            session.close()
            process.destroy()
            dir.deleteRecursively()
        }
    }
}
