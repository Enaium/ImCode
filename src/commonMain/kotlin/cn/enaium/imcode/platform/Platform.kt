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

    /**
     * Environment variable [name], falling back to the system property of the
     * same name where the platform has one. Hosts use it to point a run at a
     * different location (e.g. `IMCODE_CONFIG_DIR`), and tests use it to keep
     * their own state away from the user's.
     */
    fun getenv(name: String): String?
}