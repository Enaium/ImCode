package cn.enaium.imcode

import cn.enaium.imcode.search.SearchHit
import cn.enaium.imcode.search.SearchService
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class SearchServiceTest {

    private fun tempDir(): File {
        val dir = java.nio.file.Files.createTempDirectory("imcode-test").toFile()
        File(dir, "alpha.kt").writeText(
            """
            package demo
            fun main() {
                println("hello")
                val target = 42
            }
            """.trimIndent(),
        )
        File(dir, "beta.java").writeText("class Beta { /* hello target */ }")
        File(dir, "sub").mkdirs()
        File(dir, "sub/gamma.txt").writeText("target here too")
        return dir
    }

    private fun awaitResults(
        service: SearchService,
        root: String,
        mode: SearchService.Mode,
        query: String,
    ): List<SearchHit> = runBlocking {
        val results = ArrayList<SearchHit>()
        service.onResults = { _, hits -> results.addAll(hits) }
        service.search(root, mode, query)
        withTimeout(10_000) {
            while (results.isEmpty()) {
                kotlinx.coroutines.delay(20)
            }
        }
        results
    }

    @Test
    fun fileNameSearchFindsMatchingExtensions() {
        val root = tempDir()
        try {
            val hits = awaitResults(SearchService(), root.absolutePath, SearchService.Mode.FILE_NAME, "kt")
            assertTrue(hits.any { it.path.endsWith("alpha.kt") }, "should find alpha.kt, got ${hits.map { it.path }}")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun contentSearchReportsLineNumbers() {
        val root = tempDir()
        try {
            val hits = awaitResults(SearchService(), root.absolutePath, SearchService.Mode.CONTENT, "target")
            assertTrue(hits.isNotEmpty())
            val inAlpha = hits.firstOrNull { it.path.endsWith("alpha.kt") }
            assertEquals(3, inAlpha?.line, "target is on line 3 (0-based) of alpha.kt")
            val inSub = hits.firstOrNull { it.path.endsWith("gamma.txt") }
            assertEquals(0, inSub?.line)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun contentSearchRespectsCaseInsensitive() {
        val root = tempDir()
        try {
            val hits = awaitResults(SearchService(), root.absolutePath, SearchService.Mode.CONTENT, "HELLO")
            assertTrue(hits.any { it.lineText.contains("hello") })
        } finally {
            root.deleteRecursively()
        }
    }
}