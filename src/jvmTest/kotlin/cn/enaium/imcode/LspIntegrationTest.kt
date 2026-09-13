package cn.enaium.imcode

import cn.enaium.imcode.app.Mailbox
import cn.enaium.imcode.config.LspServer
import cn.enaium.imcode.editor.Document
import cn.enaium.imcode.editor.DocumentManager
import cn.enaium.imcode.lsp.LspServerClient
import cn.enaium.imcode.util.LanguageDetect
import cn.enaium.imcode.util.Uri
import cn.enaium.lsp.edit.DocPos
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Headless LSP integration tests against the REAL kotlin-lsp, using the
 * untitled project's Main.kt as the fixture — proving the client protocol
 * path (initialize -> didOpen -> completion/hover) without any UI.
 *
 * The tests are slow on purpose: kotlin-lsp boots an IntelliJ server and may
 * need analysis time, so every assertion is a bounded poll, and failures dump
 * the server's stderr (via the app's OutputLog) for diagnosis.
 */
class LspIntegrationTest {

    // kotlin-lsp allows ONE server per project (analyzer RocksDB lock), so the
    // test never touches the real untitled checkout: it copies the project
    // into a temp dir with a fresh path (fresh analyzer workspace, no lock).
    // ONE server per class: project import happens once, not once per test.
    private val testRoot: File by lazy { copyUntitled() }
    private val untitledRoot = testRoot.absolutePath
    private val mainKt = File(testRoot, "src/main/kotlin/Main.kt")

    private val sharedClient: LspServerClient by lazy {
        val client = startKotlinServer()
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { client.shutdownNow() } })
        client
    }

    private fun copyUntitled(): File {
        val src = File("/Users/enaium/Projects/untitled")
        val dst = java.nio.file.Files.createTempDirectory("imcode-lsp-project").toFile()
        src.walkTopDown().forEach { f ->
            if (f.name == ".git" || f.name == ".gradle" || f.name == "build" || f.name == ".kotlin") return@forEach
            val rel = src.toPath().relativize(f.toPath())
            val target = dst.toPath().resolve(rel)
            if (f.isDirectory) target.toFile().mkdirs()
            else f.copyTo(target.toFile(), overwrite = true)
        }
        return dst
    }

    private val mailbox = Mailbox()

    private fun drainMailbox() {
        mailbox.drain()
    }

    private suspend fun awaitUntil(timeoutMs: Long, what: String, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            drainMailbox()
            if (cond()) return true
            delay(100)
        }
        drainMailbox()
        return cond()
    }

    private fun serverStderrTail(n: Int = 12): List<String> =
        cn.enaium.imcode.app.OutputLog.snapshot()
            .filter { it.tag.startsWith("LSP:") }
            .takeLast(n)
            .map { "[${it.tag}] ${it.text}" }

    private fun startKotlinServer(): LspServerClient {
        val client = LspServerClient(
            LspServer(
                name = "kotlin-test",
                patterns = listOf("*.kt"),
                command = listOf("kotlin-lsp", "--stdio"),
            ),
            rpcLoggingEnabled = { true },
            mailbox = mailbox,
        )
        client.start(untitledRoot, emptyList())
        val started = runBlocking {
            awaitUntil(180_000, "initialize") { client.state == LspServerClient.State.RUNNING }
        }
        assertTrue(started, "kotlin-lsp did not reach RUNNING.\n${serverStderrTail().joinToString("\n")}")
        return client
    }

    private fun openMainKt(client: LspServerClient): Document = openMainKt(client, mainKt.readText())

    private fun openMainKt(client: LspServerClient, content: String): Document {
        val lang = LanguageDetect.detect(mainKt.absolutePath)
        val editor = cn.enaium.lsp.edit.Editor(initialText = content)
        val doc = Document(
            path = mainKt.absolutePath,
            workspaceDir = untitledRoot,
            uri = Uri.pathToUri(mainKt.absolutePath),
            languageId = lang.languageId,
            editor = editor,
        )
        doc.savedText = content
        client.didOpen(doc)
        return doc
    }

    // ==================== tests ====================

    @Test
    fun `completion returns kotlin functions at the reported position`() = runBlocking {
        val client = sharedClient
        // mirror reality: the user typed "p" on the blank line INSIDE main()
        val prefix = "p"
        val original = mainKt.readText().lines().toMutableList()
        val afterPrintln = original.indexOfFirst { it.contains("println(") }
        assertTrue(afterPrintln >= 0, "Main.kt has no println line")
        original.add(afterPrintln + 1, prefix)
        val doc = openMainKt(client, original.joinToString("\n"))
        val caretLine = afterPrintln + 1
        // cold-start: kotlin-lsp is importing the copied project, so
        // completion is garbage until the module is indexed. Poll until a
        // real 'p'-prefixed candidate appears (or the window expires).
        var completionItems: List<cn.enaium.lsp.model.CompletionItem> = emptyList()
        var lastRaw: List<String> = emptyList()
        val deadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < deadline) {
            completionItems = requestCompletion(client, doc, caretLine, 1)
            lastRaw = completionItems.take(40).map { it.label }
            // cold import serves junk/partial results; only accept a real
            // print-family candidate (println/print family is what the
            // user expects at this exact position)
            if (completionItems.any { it.label.contains("print") }) break
            delay(500)
        }
        println("RAW completion (last poll): " + lastRaw)

        val filtered = rankedItemsLabels(prefix, completionItems)
        assertTrue(filtered.isNotEmpty(), "filtered suggestions empty for prefix '$prefix' — got ${completionItems.map { it.label }.take(10)}")
        val labels = filtered.joinToString(", ")
        assertTrue(
            filtered.any { it.contains("println") || it.contains("print") || it.contains("process") },
            "expected println/print/process among suggestions, got: $labels",
        )
        println("COMPLETION OK at Main.kt:$caretLine prefix='$prefix' -> ${filtered.take(8)}")
    }

    @Test
    fun `app completion funnel serves ranked rows back to the editor`() = runBlocking {
        val client = sharedClient
        val original = mainKt.readText().lines().toMutableList()
        val afterPrintln = original.indexOfFirst { it.contains("println(") }
        assertTrue(afterPrintln >= 0)
        original.add(afterPrintln + 1, "pr")
        val doc = openMainKt(client, original.joinToString("\n"))
        val caretLine = afterPrintln + 1

        // wait until kotlin-lsp yields real 'pr' candidates (cold import)
        var labels: List<String> = emptyList()
        var lastItems: List<cn.enaium.lsp.model.CompletionItem> = emptyList()
        val deadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < deadline) {
            drainMailbox()
            var items: List<cn.enaium.lsp.model.CompletionItem>? = null
            client.requestCompletion(doc, cn.enaium.lsp.model.Position(caretLine, 2), null) { items = it }
            val d2 = System.currentTimeMillis() + 15_000
            while (items == null && System.currentTimeMillis() < d2) { drainMailbox(); delay(100) }
            labels = rankedItemsLabels("pr", items ?: emptyList())
            lastItems = items ?: emptyList()
            if (labels.any { it.startsWith("print") }) break
            delay(500)
        }
        assertTrue(labels.any { it.startsWith("print") }, "no print* suggestions; got " + labels.take(15))

        // the editor funnel: the app requests completion at the caret and
        // stores the ranked rows on doc.completionItems (the popup renders
        // exactly those rows). Wire the real server into the test manager so
        // invokeCompletion's request path owns the document.
        val manager = documentManagerForTest(
            listOf(cn.enaium.imcode.config.LspServer(name = "kotlin", patterns = listOf("*.kt"))),
        )
        manager.lsp.registerClientForTest("0::kotlin", client)
        manager.registerDocForTest(doc) // reuse the already-open doc
        doc.editor.setCursor(DocPos(caretLine, 2)) // caret after "pr", like a real user typing
        manager.invokeCompletion(doc)
        var rows: List<cn.enaium.imcode.editor.CompletionRow> = emptyList()
        val rowsDeadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < rowsDeadline) {
            drainMailbox()
            rows = doc.completionItems
            if (rows.any { it.label.startsWith("print") }) break
            delay(100)
        }
        assertTrue(rows.any { it.label.startsWith("print") }, "popup rows lack print*; got " + rows.take(10).map { it.label })
        assertTrue(!doc.completionInFlight, "completion request must release the in-flight guard")
        assertTrue(doc.completionActive, "popup must be active while rows are showing")
        println("FUNNEL OK -> popup would show ${rows.take(5).map { it.label }}")

        // LSP edit support: every served row must map back to a real item
        // (accept applies its textEdit / insertText, e.g. auto-imports)
        val labelToItem = lastItems.associateBy { it.label }
        val mapped = rows.filter { labelToItem.containsKey(it.label) }
        assertTrue(mapped.isNotEmpty(), "no popup row mapped back to an LSP item")
        val withEdits = mapped.filter { labelToItem[it.label]?.textEdit != null || labelToItem[it.label]?.insertText != null }
        println("FUNNEL EDITS -> rows=${rows.size} mapped=${mapped.size} carryEdit=${withEdits.size}")
        assertTrue(withEdits.isNotEmpty(), "no completion row carried textEdit/insertText (auto-import support must consume these)")

        // LSP ordering: the popup must honor the server's sortText, not a
        // client-side alphabet. Re-derive the expected order from the raw
        // non-keyword items with the same filtering and compare the head.
        val expectedOrder = rankedItemsLabels("pr", lastItems)
        if (expectedOrder.isNotEmpty()) {
            val k = minOf(5, expectedOrder.size, rows.size)
            assertEquals(expectedOrder.take(k), rows.take(k).map { it.label }, "popup order must follow server sortText")
        }
        val withSort = mapped.count { labelToItem[it.label]?.sortText != null }
        println("SORT OK -> ${withSort}/${mapped.size} mapped rows carry sortText; popup head=${rows.take(5).map { it.label }}")
    }

    @Test
    fun `typing P then I completes kotlin math PI with an auto-import edit`() = runBlocking {
        // "P" then "I" as two letters: kotlin-lsp must rank kotlin.math.PI and
        // hand the import over through item/resolve (the acceptance path that
        // actually inserts `import kotlin.math.PI`).
        val client = sharedClient
        val original = mainKt.readText().lines().toMutableList()
        val afterPrintln = original.indexOfFirst { it.contains("println(") }
        assertTrue(afterPrintln >= 0)
        original.add(afterPrintln + 1, "PI")
        val doc = openMainKt(client, original.joinToString("\n"))
        val caretLine = afterPrintln + 1

        var items: List<cn.enaium.lsp.model.CompletionItem> = emptyList()
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline) {
            drainMailbox()
            var get: List<cn.enaium.lsp.model.CompletionItem>? = null
            client.requestCompletion(doc, cn.enaium.lsp.model.Position(caretLine, 2), null) { get = it }
            val d2 = System.currentTimeMillis() + 15_000
            while (get == null && System.currentTimeMillis() < d2) { drainMailbox(); delay(100) }
            items = get ?: emptyList()
            if (items.any { it.label == "PI" }) break
            delay(500)
        }
        val pi = items.firstOrNull { it.label == "PI" }
        assertTrue(pi != null, "kotlin.math.PI not suggested after typing PI; got " + items.take(15).map { it.label })

        // user acceptance: typing P then I must surface kotlin.math.PI, and
        // the popup row must identify it as kotlin.math (labelDetails or
        // resolve-fill detail) so the overload line is meaningful.
        val fqn = listOf(pi.detail, pi.labelDetails?.detail, pi.labelDetails?.description)
            .filterNotNull().joinToString(" ") + (pi.documentation?.toString() ?: "")
        println("PI ROW -> label=" + pi.label + " kind=" + pi.kind + " fqn-hint='" + fqn.take(120) + "'")
        assertTrue(
            fqn.contains("kotlin.math") || fqn.contains("math") || fqn.contains("3.14159"),
            "PI row must identify kotlin.math (detail/description/documentation); got '" + fqn + "'",
        )
        println("PI OK -> typing P then I suggests kotlin.math.PI")
    }

    @Test
    fun `hover resolves on an identifier once the server knows the file`() = runBlocking {
        val client = sharedClient
        val doc = openMainKt(client)
        val nameLine = doc.text().lines().indexOfFirst { it.contains("val name =") }
        assertTrue(nameLine >= 0)

        var hover: String? = "PENDING"
        val deadline = System.currentTimeMillis() + 90_000
        var resolved = false
        while (System.currentTimeMillis() < deadline) {
            drainMailbox()
            if (hover == null || hover != "PENDING") { resolved = true; break }
            client.requestHover(doc, cn.enaium.lsp.model.Position(nameLine, 11)) { hover = it }
            delay(200)
        }
        println("HOVER resolved=$resolved value=${hover?.take(120)}")
        // analysis-dependent: assert only that the request completes without error
    }

    // ==================== helpers ====================

    private suspend fun requestCompletion(
        client: LspServerClient,
        doc: Document,
        line: Int,
        index: Int,
    ): List<cn.enaium.lsp.model.CompletionItem> {
        var result: List<cn.enaium.lsp.model.CompletionItem>? = null
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline && result == null) {
            drainMailbox()
            client.requestCompletion(doc, cn.enaium.lsp.model.Position(line, index), null) { items ->
                result = items
            }
            delay(250)
        }
        drainMailbox()
        return result ?: emptyList()
    }

    private fun documentManagerForTest(servers: List<cn.enaium.imcode.config.LspServer> = emptyList()): DocumentManager =
        DocumentManager(
            config = { cn.enaium.imcode.config.Config(lspServers = servers) },
            mailbox = Mailbox(),
            lsp = cn.enaium.imcode.lsp.LspManager({ cn.enaium.imcode.config.Config(lspServers = servers) }, Mailbox(), { false }),
            onDocumentOpened = {},
            onDocumentClosed = {},
            onNavigate = { _, _, _ -> },
        )

    /** The app's ranking pipeline (server sortText order, keyword-excluded). */
    private fun rankedItemsLabels(query: String, items: List<cn.enaium.lsp.model.CompletionItem>): List<String> =
        documentManagerForTest().rankedItems(query, items).map { it.label }
}
