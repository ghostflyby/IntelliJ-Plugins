package dev.ghostflyby.mcp.filecontent

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class WorkspaceGlobPatternTest {

    @Test
    fun `star matches files in current directory only`() {
        val pattern = WorkspaceGlobPattern.compile("*.kt")

        assertTrue(pattern.matches("FileRoutes.kt"))
        assertFalse(pattern.matches("rest/FileRoutes.kt"))
        assertFalse(pattern.matches("FileRoutes.java"))
    }

    @Test
    fun `globstar slash matches current and nested directories`() {
        val pattern = WorkspaceGlobPattern.compile("**/*.kt")

        assertTrue(pattern.matches("FileRoutes.kt"))
        assertTrue(pattern.matches("rest/FileRoutes.kt"))
        assertTrue(pattern.matches("deep/rest/FileRoutes.kt"))
        assertFalse(pattern.matches("FileRoutes.java"))
    }

    @Test
    fun `question matches one character in one segment`() {
        val pattern = WorkspaceGlobPattern.compile("src/Foo?.kt")

        assertTrue(pattern.matches("src/Foo1.kt"))
        assertFalse(pattern.matches("src/Foo12.kt"))
        assertFalse(pattern.matches("src/nested/Foo1.kt"))
    }

    @Test
    fun `backslash escapes wildcard characters`() {
        val pattern = WorkspaceGlobPattern.compile("""literal\*.kt""")

        assertTrue(pattern.matches("literal*.kt"))
        assertFalse(pattern.matches("literalA.kt"))
    }

    @Test
    fun `invalid patterns are rejected`() {
        assertThrows<IllegalArgumentException> { WorkspaceGlobPattern.compile("") }
        assertThrows<IllegalArgumentException> { WorkspaceGlobPattern.compile("/absolute/*.kt") }
        assertThrows<IllegalArgumentException> { WorkspaceGlobPattern.compile("../*.kt") }
        assertThrows<IllegalArgumentException> { WorkspaceGlobPattern.compile("**foo/*.kt") }
        assertThrows<IllegalArgumentException> { WorkspaceGlobPattern.compile("""dangling\""") }
    }
}
