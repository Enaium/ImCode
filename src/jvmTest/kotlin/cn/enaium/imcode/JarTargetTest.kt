package cn.enaium.imcode

import cn.enaium.imcode.app.AppCore
import cn.enaium.imcode.app.Mailbox
import cn.enaium.imcode.config.ConfigStore
import cn.enaium.imcode.platform.fileReadable
import cn.enaium.imcode.platform.readFileBytes
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Go-to-definition targets inside dependencies arrive as `...jar!/entry`
 * paths (e.g. a stdlib source jar for `println`). They must be readable and
 * open as documents; an unreadable target must not leave an empty tab
 * behind (which renders as "nothing opened").
 *
 * AppCore.saveState() persists to the real ~/.imcode/config.json, so the
 * config is snapshotted and restored around these tests.
 */
class JarTargetTest {

    private val configFile = File(ConfigStore.filePath)
    private var backup: String? = null
    private val tempDirs = mutableListOf<File>()

    @BeforeTest
    fun snapshotConfig() {
        backup = if (configFile.exists()) configFile.readText() else null
    }

    @AfterTest
    fun restoreConfig() {
        val b = backup
        if (b != null) configFile.writeText(b) else configFile.delete()
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { tempDirs += it }

    private fun sourceJar(): File {
        val jar = File(tempDir("sources"), "stdlib-sources.jar")
        ZipOutputStream(jar.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("kotlin/io/Console.kt"))
            zos.write("package kotlin.io\n\nfun println(message: Any?) {}\n".toByteArray())
            zos.closeEntry()
        }
        return jar
    }

    private fun coreWithWorkspace(): Pair<AppCore, cn.enaium.imcode.ui.WorkspaceWindow> {
        val core = AppCore(Mailbox())
        core.config = core.config.copy(lspServers = emptyList())
        val ws = core.openWorkspaceWindow(dir = tempDir("ws").absolutePath)
        return core to ws
    }

    @Test
    fun readsEntryFromJar() {
        val target = "${sourceJar().absolutePath}!/kotlin/io/Console.kt"
        assertTrue(fileReadable(target))
        assertTrue(String(readFileBytes(target)).contains("fun println"))
    }

    @Test
    fun readsJarSchemeTarget() {
        val jar = sourceJar()
        val target = "jar:file://${jar.absolutePath}!/kotlin/io/Console.kt"
        assertTrue(fileReadable(target))
        assertTrue(String(readFileBytes(target)).contains("fun println"))
    }

    @Test
    fun missingEntryAndMissingFileAreNotReadable() {
        val jar = sourceJar()
        assertFalse(fileReadable("${jar.absolutePath}!/kotlin/io/Missing.kt"))
        assertFalse(fileReadable(File(tempDir("none"), "nope.kt").absolutePath))
    }

    @Test
    fun jumpIntoSourceJarOpensDocument() {
        val (core, ws) = coreWithWorkspace()
        val target = "${sourceJar().absolutePath}!/kotlin/io/Console.kt"

        core.openLocation(target, 2, 0)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !core.documents.isOpen(target)) {
            core.mailbox.drain()
            Thread.sleep(50)
        }

        assertTrue(target in ws.openTabs.toList())
        assertTrue(core.documents.isOpen(target))
    }

    @Test
    fun unreadableTargetLeavesNoTab() {
        val (core, ws) = coreWithWorkspace()

        core.openLocation(ws.dir + "/Missing.kt", 0, 0)
        core.mailbox.drain()

        assertTrue(ws.openTabs.toList().isEmpty())
    }
}
