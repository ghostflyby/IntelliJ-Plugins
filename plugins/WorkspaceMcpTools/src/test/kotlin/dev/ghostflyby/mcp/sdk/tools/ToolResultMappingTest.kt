/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk.tools

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

internal class ToolResultMappingTest {

    @Test
    fun `passes through CallToolResult`() {
        val expected = CallToolResult(content = listOf(TextContent(text = "ok")))
        assertSame(expected, mapToolResult(expected))
    }

    @Test
    fun `String to TextContent`() {
        val result = mapToolResult("hello")
        assertEquals("hello", (result.content.first() as TextContent).text)
    }

    @Test
    fun `Unit to empty`() = assertEquals(0, mapToolResult(Unit).content.size)

    @Test
    fun `null to empty`() = assertEquals(0, mapToolResult(null).content.size)

    @Test
    fun `wraps ContentBlock`() {
        val block = TextContent(text = "x")
        assertEquals(listOf(block), mapToolResult(block).content)
    }

    @Test
    fun `passes List of ContentBlock`() =
        assertEquals(
            2,
            mapToolResult(listOf<ContentBlock>(TextContent(text = "a"), TextContent(text = "b"))).content.size,
        )

    @Test
    fun `unknown type to JSON structuredContent`() {
        val value = TestResultMappingInput(listOf("x"), 1)
        val result = mapToolResult(value)
        assertEquals(result.structuredContent, Json.encodeToJsonElement(value) as? JsonObject)
    }

    @Test
    fun `empty List to empty result`() {
        val result = mapToolResult(emptyList<ContentBlock>())
        assertEquals(0, result.content.size)
    }
}

@Serializable
internal class TestResultMappingInput(
    val list: List<String>,
    val i: Int,
)
