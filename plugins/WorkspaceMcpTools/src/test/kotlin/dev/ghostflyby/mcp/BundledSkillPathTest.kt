/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp

import com.intellij.testFramework.junit5.TestApplication
import dev.ghostflyby.mcp.sdk.bundledSkillPath
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

@TestApplication
// Lives in the root project: the assertion needs the full plugin registered in the test
// application (META-INF/plugin.xml + the prepareTest sandbox with the agent-skills copy),
// which only the product project's test run provides.
class BundledSkillPathTest {

    @Test
    fun `bundled skill path`() {
        val bundledSkillPath = bundledSkillPath()
        assertNotNull(bundledSkillPath)
        assertTrue(Files.isDirectory(bundledSkillPath), "bundled skill directory must exist: $bundledSkillPath")
    }
}
