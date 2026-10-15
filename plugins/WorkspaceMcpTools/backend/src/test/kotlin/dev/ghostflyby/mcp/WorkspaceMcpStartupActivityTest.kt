/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

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
