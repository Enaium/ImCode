package cn.enaium.imcode.platform

import cn.enaium.lsp.jsonrpc.StreamMessageTransport
import java.io.File

private class JvmPlatformProcess(
    private val process: Process,
    override val transport: StreamMessageTransport,
) : PlatformProcess {
    override fun destroy() {
        try { process.destroy() } catch (_: Throwable) {}
    }

    override fun waitFor(timeoutMs: Long): Boolean =
        try {
            process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            false
        }
}

actual fun launchRpcProcess(
    command: List<String>,
    cwd: String?,
    env: Map<String, String>,
    onStderr: (String) -> Unit,
): PlatformProcess {
    val builder = ProcessBuilder(command)
    if (cwd != null) builder.directory(File(cwd))
    builder.redirectErrorStream(false)
    env.forEach { (k, v) -> builder.environment()[k] = v }
    val process = builder.start()

    // stderr -> log line reader (stdout is the RPC channel!)
    val stderrReader = Thread {
        try {
            process.errorStream.bufferedReader().forEachLine { line ->
                if (line.isNotBlank()) onStderr(line)
            }
        } catch (_: Throwable) {
        }
    }
    stderrReader.isDaemon = true
    stderrReader.name = "rpc-stderr"
    stderrReader.start()

    return JvmPlatformProcess(process, StreamMessageTransport(process.inputStream, process.outputStream))
}