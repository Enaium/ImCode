package cn.enaium.imcode

import cn.enaium.imcode.util.Glob
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlobTest {
    @Test
    fun starPatternMatchesFileNames() {
        assertTrue(Glob.matches("*.kt", "Main.kt"))
        assertTrue(Glob.matches("*.kts", "build.gradle.kts"))
        assertFalse(Glob.matches("*.kt", "build.gradle.kts"))
        assertFalse(Glob.matches("*.kt", "Main.java"))
        assertFalse(Glob.matches("*.kt", "Main.ktx"))
    }

    @Test
    fun questionMarkMatchesSingleCharacter() {
        assertTrue(Glob.matches("Main.??", "Main.kt"))
        assertFalse(Glob.matches("Main.??", "Main.kts"))
    }

    @Test
    fun pathPatternMatchesRelativePath() {
        assertTrue(Glob.matches("src/*/Main.kt", "Main.kt", "src/main/Main.kt"))
        assertFalse(Glob.matches("src/*/Main.kt", "Main.kt", "test/main/Main.kt"))
    }

    @Test
    fun matchesAnyAcrossPatterns() {
        assertTrue(Glob.matchesAny(listOf("*.java", "*.kt"), "Foo.kt"))
        assertFalse(Glob.matchesAny(listOf("*.java", "*.py"), "Foo.kt"))
    }
}