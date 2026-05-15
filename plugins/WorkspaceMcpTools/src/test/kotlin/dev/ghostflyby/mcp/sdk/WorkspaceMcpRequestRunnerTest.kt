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

package dev.ghostflyby.mcp.sdk

import dev.ghostflyby.mcp.sdk.tools.WorkspaceMcpProjectToolArguments
import dev.ghostflyby.mcp.sdk.tools.sdkToolDescriptor
import dev.ghostflyby.mcp.sdk.tools.toolArgsJson
import dev.ghostflyby.mcp.vfs.tools.VfsExistsResult
import dev.ghostflyby.mcp.vfs.tools.encodeVfsExistsResult
import dev.ghostflyby.mcp.vfs.tools.vfsExistsSdkTool
import dev.ghostflyby.mcp.vfs.tools.vfsRefreshSdkTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

internal class WorkspaceMcpRequestRunnerTest {

    @Test
    fun `sdkToolDescriptor builds typed descriptor with serializer`() {
        @Serializable
        data class TestArgs(val url: String, val count: Int = 0)
        val descriptor = sdkToolDescriptor<TestArgs>(
            name = "test_tool",
            description = "A test tool",
        ) { error("not called") }
        assertEquals("test_tool", descriptor.name)
        assertTrue(descriptor.serializer.descriptor.serialName.contains("TestArgs"))
    }

    @Test
    fun `serialization decodes defaults when partial args`() {
        @Serializable
        data class TestArgs(
            val url: String,
            val count: Int = 42,
            val async: Boolean = true,
        )
        val json = buildJsonObject { put("url", "file:///test.txt") }
        val decoded = toolArgsJson.decodeFromJsonElement(
            kotlinx.serialization.serializer<TestArgs>(), json
        )
        assertEquals("file:///test.txt", decoded.url)
        assertEquals(42, decoded.count)
        assertEquals(true, decoded.async)
    }

    @Test
    fun `serialization decodes empty object with defaults`() {
        @Serializable
        data class TestArgs(
            val url: String = "",
            val projectKey: String? = null,
            val async: Boolean = false,
        )
        val decoded = toolArgsJson.decodeFromJsonElement(
            kotlinx.serialization.serializer<TestArgs>(), buildJsonObject { }
        )
        assertEquals("", decoded.url)
        assertNull(decoded.projectKey)
        assertEquals(false, decoded.async)
    }

    @Test
    fun `serialization fails on bad type`() {
        @Serializable
        data class TestArgs(val count: Int)
        val json = buildJsonObject { put("count", JsonPrimitive("not-an-int")) }
        assertTrue(
            kotlin.runCatching {
                toolArgsJson.decodeFromJsonElement(
                    kotlinx.serialization.serializer<TestArgs>(), json
                )
            }.exceptionOrNull() is SerializationException
        )
    }

    @Test
    fun `serialization fails on missing required field`() {
        @Serializable
        data class TestArgs(val url: String)
        assertTrue(
            kotlin.runCatching {
                toolArgsJson.decodeFromJsonElement(
                    kotlinx.serialization.serializer<TestArgs>(), buildJsonObject { }
                )
            }.exceptionOrNull() is SerializationException
        )
    }

    @Test
    fun `project tool args interface`() {
        @Serializable
        data class MyArgs(
            val url: String,
            override val projectKey: String? = null,
            override val projectPath: String? = null,
        ) : WorkspaceMcpProjectToolArguments

        val args = MyArgs(url = "/test", projectKey = "my-key", projectPath = "/tmp")
        assertEquals("my-key", args.projectKey)
        assertEquals("/tmp", args.projectPath)
    }

    @Test
    fun `vfs refresh sdk schema uses DTO field types`() {
        val schema = vfsRefreshSdkTool().inputSchema
        assertEquals("string", schema.propertyType("url"))
        assertEquals("string", schema.propertyType("projectKey"))
        assertEquals("string", schema.propertyType("projectPath"))
        assertEquals("boolean", schema.propertyType("async"))
        assertEquals("boolean", schema.propertyType("recursive"))
    }

    @Test
    fun `vfs exists sdk schema uses DTO field types`() {
        val schema = vfsExistsSdkTool().inputSchema
        assertEquals("string", schema.propertyType("url"))
        assertEquals("string", schema.propertyType("projectKey"))
        assertEquals("string", schema.propertyType("projectPath"))
        assertTrue(schema.required?.contains("url") == true)
    }

    @Test
    fun `vfs exists result encodes deterministic json`() {
        assertEquals(
            """{"url":"file:///tmp/example.txt","exists":true}""",
            encodeVfsExistsResult(VfsExistsResult(url = "file:///tmp/example.txt", exists = true)),
        )
    }

    private fun io.modelcontextprotocol.kotlin.sdk.types.ToolSchema.propertyType(name: String): String? {
        val property = properties?.get(name) as? JsonObject ?: return null
        return (property["type"] as? JsonPrimitive)?.content
    }
}
