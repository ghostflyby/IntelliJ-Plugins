/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.server.tools

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

internal class ToolResultMappingTest {

    @Test
    fun `passes through CallToolResult`() {
        val expected = CallToolResult(content = listOf(TextContent(text = "ok")))
        assertSame(expected, expected.toMcpCallToolResult<CallToolResult>())
    }

    @Test
    fun `String to TextContent`() {
        val result = "hello".toMcpCallToolResult()
        assertEquals("hello", (result.content.first() as TextContent).text)
    }

    @Test
    fun `Unit to empty`() = assertEquals(0, Unit.toMcpCallToolResult().content.size)

    @Test
    fun `null to empty`() = assertEquals(0, null.toMcpCallToolResult().content.size)

    @Test
    fun `wraps ContentBlock`() {
        val block = TextContent(text = "x")
        assertEquals(listOf(block), block.toMcpCallToolResult().content)
    }

    @Test
    fun `passes List of ContentBlock`() =
        assertEquals(
            2,
            listOf<ContentBlock>(TextContent(text = "a"), TextContent(text = "b")).toMcpCallToolResult().content.size,
        )

    @Test
    fun `unknown type to JSON structuredContent`() {
        val value = TestResultMappingInput(listOf("x"), 1)
        val result = value.toMcpCallToolResult()
        assertEquals(result.structuredContent, Json.encodeToJsonElement(value) as? JsonObject)
    }

    @Test
    fun `empty ContentBlock List to empty result`() {
        val result = emptyList<ContentBlock>().toMcpCallToolResult()
        assertEquals(0, result.content.size)
    }

    @Test
    fun `list to JSON`() {
        val result = listOf("x", "y").toMcpCallToolResult()
        assertEquals(result.structuredContent?.get("result"), Json.encodeToJsonElement(listOf("x", "y")))
    }

    @Test
    fun `empty string list to JSON`() {
        val result = emptyList<String>().toMcpCallToolResult()
        assertEquals(result.structuredContent?.get("result"), Json.encodeToJsonElement(emptyList<String>()))
    }

    @Test
    fun `int to JSON`() {
        val result = 1.toMcpCallToolResult()
        assertEquals(result.structuredContent?.get("result"), Json.encodeToJsonElement(1))
    }
}

@Serializable
internal class TestResultMappingInput(
    val list: List<String>,
    val i: Int,
)
