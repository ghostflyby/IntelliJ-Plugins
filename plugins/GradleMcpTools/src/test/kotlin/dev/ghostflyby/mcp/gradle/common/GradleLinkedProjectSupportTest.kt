/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * This file is part of IntelliJ-Plugins by ghostflyby
 *
 * IntelliJ-Plugins by ghostflyby is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see
 * <https://www.gnu.org/licenses/>.
 */

package dev.ghostflyby.mcp.gradle.common

import com.intellij.mcpserver.McpExpectedError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

internal class GradleLinkedProjectSupportTest {
    @Test
    fun `matches exact linked project path before suffix lookup`() {
        val linkedProjectPaths = listOf(
            Path.of("/repo/root"),
            Path.of("/repo/root/plugins/GradleMcpTools"),
        )

        val matched = matchLinkedProjectPath(linkedProjectPaths, Path.of("/repo/root"))

        assertEquals(Path.of("/repo/root"), matched)
    }

    @Test
    fun `matches unique suffix project path`() {
        val linkedProjectPaths = listOf(
            Path.of("/repo/root"),
            Path.of("/repo/root/plugins/GradleMcpTools"),
        )

        val matched = matchLinkedProjectPath(linkedProjectPaths, Path.of("plugins/GradleMcpTools"))

        assertEquals(Path.of("/repo/root/plugins/GradleMcpTools"), matched)
    }

    @Test
    fun `fails on ambiguous suffix matches`() {
        val linkedProjectPaths = listOf(
            Path.of("/repo-a/plugins/GradleMcpTools"),
            Path.of("/repo-b/plugins/GradleMcpTools"),
        )

        val error = expectMcpExpectedError {
            matchLinkedProjectPath(linkedProjectPaths, Path.of("plugins/GradleMcpTools"))
        }

        assertTrue(error.message.orEmpty().contains("Ambiguous Gradle project path"))
    }

    @Test
    fun `fails when requested project path is not linked`() {
        val linkedProjectPaths = listOf(Path.of("/repo/root"))

        val error = expectMcpExpectedError {
            matchLinkedProjectPath(linkedProjectPaths, Path.of("/repo/other"))
        }

        assertTrue(error.message.orEmpty().contains("is not linked"))
    }

    private fun expectMcpExpectedError(block: () -> Unit): McpExpectedError {
        try {
            block()
        } catch (error: McpExpectedError) {
            return error
        }
        throw AssertionError("Expected McpExpectedError to be thrown.")
    }
}
