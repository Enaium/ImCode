package cn.enaium.imcode.platform

actual object Platform {
    private val os = System.getProperty("os.name").lowercase()
    actual val isMac: Boolean = os.contains("mac")
    actual val isWindows: Boolean = os.contains("win")
    actual val isLinux: Boolean = os.contains("linux")
    actual val userHome: String = System.getProperty("user.home") ?: "."
    actual fun currentTimeMillis(): Long = System.currentTimeMillis()
    actual fun processId(): Int = ProcessHandle.current().pid().toInt()
}