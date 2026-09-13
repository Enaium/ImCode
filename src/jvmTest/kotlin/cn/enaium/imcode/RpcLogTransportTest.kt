package cn.enaium.imcode

import cn.enaium.imcode.app.OutputLog
import cn.enaium.imcode.lsp.RpcLogTransport
import cn.enaium.lsp.jsonrpc.MessageTransport
import kotlin.test.Test
import kotlin.test.assertTrue

class RpcLogTransportTest {
    private class FakeTransport : MessageTransport {
        override fun send(message: String) {}
        override fun receive(): String? = null
    }

    @Test
    fun didChangeRendersChangeContents() {
        OutputLog.clear()
        val t = RpcLogTransport(FakeTransport(), "test", { true })
        t.send("""{"jsonrpc":"2.0","method":"textDocument/didChange","params":{"textDocument":{"uri":"file:///x/Main.kt","version":5},"contentChanges":[{"range":{"start":{"line":12,"character":2},"end":{"line":12,"character":5}},"rangeLength":3,"text":"abb"}]}}""")
        val texts = OutputLog.snapshot().joinToString("\n") { it.text }
        println("----\n$texts\n----")
        assertTrue(texts.contains("contentChanges=[{range={start={line=12 character=2} end={line=12 character=5}} rangeLength=3 text=abb}]"), "change contents missing:\n$texts")
    }

    @Test
    fun completionFrameLogsSummaryAndFullJson() {
        OutputLog.clear()
        val t = RpcLogTransport(FakeTransport(), "test", { true })
        t.send("""{"jsonrpc":"2.0","id":40,"method":"textDocument/completion","params":{"textDocument":{"uri":"file:///x/Main.kt"},"position":{"line":12,"character":4},"context":{"triggerKind":1}}}""")
        val entries = OutputLog.snapshot()
        val texts = entries.joinToString("\n") { "[${it.level}] ${it.text}" }
        println("----\n$texts\n----")
        assertTrue(texts.contains("[send] textDocument/completion req#40"), "summary line missing:\n$texts")
        // nested params render WITH their values (position coords, triggerKind)
        assertTrue(texts.contains("position={line=12 character=4}"), "rich summary missing:\n$texts")
        assertTrue(texts.contains(""""method":"textDocument/completion""""), "full JSON line missing:\n$texts")
        assertTrue(texts.contains(""""triggerKind":1"""), "full params missing:\n$texts")
    }
}
