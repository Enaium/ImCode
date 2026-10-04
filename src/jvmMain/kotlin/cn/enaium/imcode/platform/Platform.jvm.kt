package cn.enaium.imcode.platform

actual object Platform {

    /** System property first: a test can override what the environment says. */
    actual fun getenv(name: String): String? = System.getProperty(name) ?: System.getenv(name)
    private val os = System.getProperty("os.name").lowercase()
    actual val isMac: Boolean = os.contains("mac")
    actual val isWindows: Boolean = os.contains("win")
    actual val isLinux: Boolean = os.contains("linux")
    actual val userHome: String = System.getProperty("user.home") ?: "."
    actual fun currentTimeMillis(): Long = System.currentTimeMillis()

    actual fun timeOfDay(): String =
        java.time.LocalTime.now().let { "%02d:%02d:%02d".format(it.hour, it.minute, it.second) }
    actual fun processId(): Int = ProcessHandle.current().pid().toInt()
}