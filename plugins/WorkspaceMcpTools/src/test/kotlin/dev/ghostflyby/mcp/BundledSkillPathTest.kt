/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp

import com.intellij.testFramework.junit5.TestApplication
import dev.ghostflyby.mcp.sdk.bundledSkillPath
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import java.nio.file.Files

@TestApplication
class BundledSkillPathTest {

    @Test
    fun `bundled skill path`() {
        val bundledSkillPath = bundledSkillPath()
        assertNotNull(bundledSkillPath)
        Files.exists(bundledSkillPath)
    }

}