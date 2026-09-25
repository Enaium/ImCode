package cn.enaium.imcode

import java.io.File

/**
 * A Python interpreter with debugpy installed, or null.
 *
 * The debug tests need a real adapter; on a machine without one there is
 * nothing to debug, so they say so instead of failing. Point
 * `IMCODE_DEBUGPY_PYTHON` at an interpreter to use a different one.
 */
internal fun debugpyPython(): String? {
    val candidates = listOfNotNull(
        System.getenv("IMCODE_DEBUGPY_PYTHON"),
        "${System.getProperty("user.home")}/.imcode/tools/venv/bin/python",
        "/usr/bin/python3",
    )
    return candidates.firstOrNull { path ->
        val file = File(path)
        if (!file.canExecute()) return@firstOrNull false
        try {
            ProcessBuilder(path, "-c", "import debugpy").start().waitFor() == 0
        } catch (_: Throwable) {
            false
        }
    }
}

/** A temp directory holding [name] with [content]; the caller deletes it. */
internal fun tempPythonScript(name: String, content: String): Pair<File, File> {
    val dir = File.createTempFile("imcode-debug", "").let { f ->
        f.delete()
        f.mkdirs()
        f
    }
    val script = File(dir, name)
    script.writeText(content)
    return dir to script
}
