package dev.ghostflyby.mcp

import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

@TestApplication
internal class WorkspaceMcpStartupActivityTest {
    @Test
    fun `workspace mcp server does not start in unit test mode`() {
        assertFalse(shouldStartWorkspaceMcpServer())
    }
}
