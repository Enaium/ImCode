package cn.enaium.imcode.platform

/**
 * Host facts. JVM actuals use System properties; future native targets use
 * kotlin.native.Platform / NSProcessInfo.
 */
expect object Platform {
    val isMac: Boolean
    val isWindows: Boolean
    val isLinux: Boolean
    val userHome: String
    fun currentTimeMillis(): Long

    /** PID of the current process, for LSP `initialize.processId`. */
    fun processId(): Int
}