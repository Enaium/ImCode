package cn.enaium.imcode.platform

import java.io.File
import java.nio.file.Paths

private class JvmIoFile(private val file: File) : IoFile {
    override val absolutePath: String get() = file.absolutePath
    override val name: String get() = file.name
    override val parent: IoFile? get() = file.parentFile?.let { JvmIoFile(it) }
    override val extension: String get() = file.extension
    override val isFile: Boolean get() = file.isFile
    override val isDirectory: Boolean get() = file.isDirectory
    override val exists: Boolean get() = file.exists()

    override fun readText(): String = file.readText()
    override fun readBytes(): ByteArray = file.readBytes()
    override fun writeText(text: String) = file.writeText(text)
    override fun createNewFile(): Boolean = file.createNewFile()
    override fun mkdirs(): Boolean = file.mkdirs()

    override fun listFiles(): List<IoFile>? =
        file.listFiles()?.filter { it.name != ".git" }?.map { JvmIoFile(it) }

    override fun length(): Long = file.length()

    override fun relativize(other: IoFile): String =
        Paths.get(file.absolutePath).relativize(Paths.get(other.absolutePath))
            .toString()
            .replace(File.separatorChar, '/')
}

actual fun ioFile(path: String): IoFile = JvmIoFile(File(path))

/**
 * Normalized archive target: `jar:file:///lib.jar!/entry.kt` and
 * `file:///lib.jar!/entry.kt` both become `/lib.jar!/entry.kt`; plain paths
 * pass through untouched.
 */
private fun normalizeJarTarget(path: String): String {
    var p = path
    if (p.startsWith("jar:")) p = p.removePrefix("jar:")
    if (p.startsWith("file://")) p = p.removePrefix("file://")
    else if (p.startsWith("file:")) p = p.removePrefix("file:")
    return p
}

private fun <T> withJarEntry(
    path: String,
    entry: (java.util.zip.ZipFile, java.util.zip.ZipEntry) -> T,
): T? {
    val normalized = normalizeJarTarget(path)
    val sep = normalized.indexOf("!/")
    if (sep < 0) return null
    val jarPath = normalized.substring(0, sep)
    val entryName = normalized.substring(sep + 2).removePrefix("/")
    return try {
        java.util.zip.ZipFile(jarPath).use { zf ->
            val e = zf.getEntry(entryName) ?: return null
            entry(zf, e)
        }
    } catch (t: Throwable) {
        null
    }
}

actual fun readFileBytes(path: String): ByteArray {
    withJarEntry(path) { zf, e -> zf.getInputStream(e).readBytes() }?.let { return it }
    val normalized = normalizeJarTarget(path)
    if (normalized.contains("!/")) {
        throw java.io.FileNotFoundException("archive entry not found: $path")
    }
    return File(normalized).readBytes()
}

actual fun fileReadable(path: String): Boolean {
    if (normalizeJarTarget(path).contains("!/")) {
        return withJarEntry(path) { _, _ -> true } == true
    }
    return File(normalizeJarTarget(path)).isFile
}