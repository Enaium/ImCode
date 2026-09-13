package cn.enaium.imcode

import kotlin.test.Test

class NativeSymbolProbeTest {
    @Test
    fun probeNewJniSymbols() {
        val url = cn.enaium.imgui.ImGui::class.java
            .getResource("/cn/enaium/imgui/native/darwin-aarch64/libimgui_jni.dylib")
        println("PROBE RESOURCE ${if (url != null) url.toString().take(160) else null}")
        val cp = System.getProperty("java.class.path").split(java.io.File.pathSeparator)
        for (e in cp) if (e.contains("jni") || e.contains("imgui-kmp-jvm")) println("PROBE CP " + e.takeLast(90))
        val tmpRoot = java.io.File(System.getProperty("java.io.tmpdir"))
        val dirs = tmpRoot.listFiles { f -> f.isDirectory && f.name.startsWith("imgui-kmp-") } ?: emptyArray()
        for (d in dirs.take(3)) {
            val lib = java.io.File(d, "libimgui_jni.dylib")
            if (lib.isFile) println("PROBE EXTRACTED ${d.name} size=${lib.length()}")
        }
        // the editor widget is a pure Kotlin class now (no native surface of
        // its own); construct one and drive the pure text API to prove the
        // class loads on the JVM classpath.
        val editor = cn.enaium.lsp.edit.Editor(initialText = "fun main() {}")
        try {
            fun probe(name: String, block: () -> Unit) {
                try {
                    block()
                    println("PROBE OK    $name")
                } catch (t: Throwable) {
                    println("PROBE FAIL  $name : ${t.javaClass.simpleName}: ${t.message}")
                }
            }
            probe("setText/getText/lineCount") {
                editor.setText("val x = 1")
                check(editor.getText() == "val x = 1") { "round-trip mismatch" }
                check(editor.lineCount() == 1)
                check(editor.buffer.line(0) == "val x = 1")
            }
            probe("setCursor/insertText") {
                editor.setText("ab")
                editor.setCursor(cn.enaium.lsp.edit.DocPos(0, 1))
                editor.insertText(editor.cursor, "X")
                check(editor.getText() == "aXb") { "insert mismatch: ${editor.getText()}" }
            }
        } finally {
            // Editor holds no native resources; nothing to release.
        }
    }
}
