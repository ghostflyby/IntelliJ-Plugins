/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import dev.ghostflyby.mcp.sdk.tools.parseToolArguments
import dev.ghostflyby.mcp.sdk.tools.toolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class WorkspaceMcpRequestRunnerTest {

    @Test
    fun `tool arg parsing extracts projectKey and projectPath`() {
        val args = buildJsonObject {
            put("projectKey", "my-project-a1b2")
            put("projectPath", "/tmp/workspace")
            put("url", "file:///tmp/file.txt")
        }
        val parsed = parseToolArguments(args)
        assertEquals("my-project-a1b2", parsed["projectKey"])
        assertEquals("/tmp/workspace", parsed["projectPath"])
        assertEquals("file:///tmp/file.txt", parsed["url"])
    }

    @Test
    fun `tool arg parsing handles null args`() {
        val parsed = parseToolArguments(null)
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun `tool arg parsing returns empty map for no args`() {
        val parsed = parseToolArguments(buildJsonObject { })
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun `tool arg parsing omits blank values`() {
        val args = buildJsonObject {
            put("projectKey", "")
            put("url", "file:///f.txt")
        }
        val parsed = parseToolArguments(args)
        assertNull(parsed["projectKey"])
        assertEquals("file:///f.txt", parsed["url"])
    }

    @Test
    fun `tool schema helper builds correct schema`() {
        val schema = toolSchema(
            properties = mapOf("projectKey" to "Project key", "url" to "VFS URL"),
            required = listOf("url"),
        )
        assertTrue(schema.required?.contains("url") == true)
    }

    @Test
    fun `empty tool schema has null required`() {
        val schema = toolSchema(properties = emptyMap())
        assertNull(schema.required)
    }
}
