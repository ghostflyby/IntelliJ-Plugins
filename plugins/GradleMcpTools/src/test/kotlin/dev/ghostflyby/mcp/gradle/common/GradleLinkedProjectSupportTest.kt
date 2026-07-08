/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.gradle.common

import com.intellij.mcpserver.McpExpectedError
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.nio.file.Path

internal class GradleLinkedProjectSupportTest {
    @Test
    fun `matches exact linked project path before suffix lookup`() {
        val linkedProjectPaths = listOf(
            Path.of("/repo/root"),
            Path.of("/repo/root/plugins/GradleMcpTools"),
        )

        val matched = matchLinkedProjectPath(linkedProjectPaths, Path.of("/repo/root"))

        Assertions.assertEquals(Path.of("/repo/root"), matched)
    }

    @Test
    fun `matches unique suffix project path`() {
        val linkedProjectPaths = listOf(
            Path.of("/repo/root"),
            Path.of("/repo/root/plugins/GradleMcpTools"),
        )

        val matched = matchLinkedProjectPath(linkedProjectPaths, Path.of("plugins/GradleMcpTools"))

        Assertions.assertEquals(Path.of("/repo/root/plugins/GradleMcpTools"), matched)
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

        Assertions.assertTrue(error.message.orEmpty().contains("Ambiguous Gradle project path"))
    }

    @Test
    fun `fails when requested project path is not linked`() {
        val linkedProjectPaths = listOf(Path.of("/repo/root"))

        val error = expectMcpExpectedError {
            matchLinkedProjectPath(linkedProjectPaths, Path.of("/repo/other"))
        }

        Assertions.assertTrue(error.message.orEmpty().contains("is not linked"))
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
