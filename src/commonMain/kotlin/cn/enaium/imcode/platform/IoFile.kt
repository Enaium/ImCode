package cn.enaium.imcode.platform

/**
 * Minimal platform-independent file handle used by the shared code.
 * JVM actual wraps java.io.File; native actuals could wrap the POSIX API.
 */
interface IoFile {
    val absolutePath: String
    val name: String
    val parent: IoFile?
    val extension: String
    val isFile: Boolean
    val isDirectory: Boolean
    val exists: Boolean

    fun readText(): String

    /** Raw file bytes (used for binary/text detection and hex view). */
    fun readBytes(): ByteArray

    fun writeText(text: String)
    fun createNewFile(): Boolean
    fun mkdirs(): Boolean

    /** Directory children or null when not a directory / unreadable. */
    fun listFiles(): List<IoFile>?

    fun length(): Long

    /** Path of [other] relative to this directory, '/' separated. */
    fun relativize(other: IoFile): String
}

expect fun ioFile(path: String): IoFile

/**
 * Bytes of [path] for the editor. Besides regular files this understands the
 * `...jar!/entry` form that language servers return for library definitions
 * (e.g. a stdlib source jar), so go-to-definition into a dependency works.
 * Throws when the file or archive entry is missing or unreadable.
 */
expect fun readFileBytes(path: String): ByteArray

/**
 * Whether [readFileBytes] could succeed (cheap check: entry lookup, no read).
 * Callers use it to avoid creating a tab for a target that cannot be opened.
 */
expect fun fileReadable(path: String): Boolean

/**
 * Whether [path] points inside an archive (`/lib.jar!/pkg/A.kt`, or the
 * `jar:file://…` URI form). Such documents are read-only: the bytes live in
 * a zip entry that the editor must never write back.
 */
fun isArchiveEntryPath(path: String): Boolean =
    path.contains("!/") || path.startsWith("jar:")

/** True when [path] is [base] or lives under it (path-component aware). */
fun isUnder(base: String, path: String): Boolean {
    val b = base.trimEnd('/').trimEnd('\\')
    val p = path.trimEnd('/')
    if (p == b) return true
    return p.startsWith(b + "/") || p.startsWith(b + "\\")
}