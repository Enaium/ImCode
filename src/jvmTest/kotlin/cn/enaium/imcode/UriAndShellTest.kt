package cn.enaium.imcode

import cn.enaium.imcode.util.ShellWords
import cn.enaium.imcode.util.Uri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShellWordsTest {
    @Test
    fun splitsSimpleCommand() {
        assertEquals(listOf("kotlin-lsp", "--stdio"), ShellWords.split("kotlin-lsp --stdio"))
    }

    @Test
    fun keepsQuotedArgumentsIntact() {
        assertEquals(listOf("echo", "hello world", "'x'"), ShellWords.split("""echo "hello world" "'x'""""))
    }

    @Test
    fun emptyInput() {
        assertTrue(ShellWords.split("   ").isEmpty())
    }

    @Test
    fun joinQuotesTokensWithSpaces() {
        assertEquals(
            "kotlin-lsp \"--log-level=debug info\"",
            ShellWords.join(listOf("kotlin-lsp", "--log-level=debug info")),
        )
    }

    @Test
    fun joinLeavesPlainTokensBare() {
        assertEquals("kotlin-lsp --stdio", ShellWords.join(listOf("kotlin-lsp", "--stdio")))
    }

    @Test
    fun splitJoinRoundTripPreservesQuotes() {
        val command = "kotlin-lsp \"--log-level=debug info\" --verbose"
        val tokens = ShellWords.split(command)
        val rejoined = ShellWords.join(tokens)
        assertEquals(command, rejoined)
        assertEquals(tokens, ShellWords.split(rejoined))
    }

    @Test
    fun joinEscapesEmbeddedQuotesAndBackslashes() {
        val tokens = listOf("echo", "say \"hi\"", "C:\\path")
        val joined = ShellWords.join(tokens)
        assertEquals(tokens, ShellWords.split(joined))
    }
}

class UriTest {
    @Test
    fun roundTripWithSpacesAndUnicode() {
        val path = "/Users/enaium/My Project/测试 file.kt"
        val uri = Uri.pathToUri(path)
        assertTrue(uri.startsWith("file://"))
        assertEquals(path, Uri.uriToPath(uri))
    }

    @Test
    fun nonFileUriReturnsNull() {
        assertNull(Uri.uriToPath("untitled:untitled-1"))
    }

    @Test
    fun windowsStyleUri() {
        assertEquals("/c:/Users", Uri.uriToPath("file:///c:/Users"))
    }
}