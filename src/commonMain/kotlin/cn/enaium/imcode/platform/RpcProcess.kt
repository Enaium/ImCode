package cn.enaium.imcode.platform

import cn.enaium.lsp.jsonrpc.MessageTransport

/**
 * A spawned RPC subprocess whose stdin/stdout carry framed JSON-RPC
 * ([transport]) and whose stderr is streamed into [onStderr].
 */
interface PlatformProcess {
    val transport: MessageTransport

    fun destroy()

    /** True when the process exited within [timeoutMs] (after destroy). */
    fun waitFor(timeoutMs: Long): Boolean
}

/** Starts [command] with working directory [cwd]; stderr lines go to [onStderr]. */
expect fun launchRpcProcess(
    command: List<String>,
    cwd: String?,
    env: Map<String, String>,
    onStderr: (String) -> Unit,
): PlatformProcess