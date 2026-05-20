/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk.tools

import dev.ghostflyby.mcp.route.McpCallContext
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.Serializable
import org.junit.Assert.*
import org.junit.Test

internal class ToolRegistryTest {

    @Test
    fun `reflectTools skips non-suspend functions`() {
        assertTrue(reflectTools(NonSuspendTool::class).isEmpty())
    }

    @Test
    fun `reflectTools skips wrong receiver type`() {
        assertTrue(reflectTools(WrongReceiverTool::class).isEmpty())
    }

    @Test
    fun `mapToolResult passes through CallToolResult`() {
        val expected = CallToolResult(content = listOf(TextContent(text = "ok")))
        assertSame(expected, mapToolResult(expected))
    }

    @Test
    fun `mapToolResult wraps String into TextContent`() {
        val result = mapToolResult("hello")
        assertEquals("hello", (result.content.first() as TextContent).text)
    }

    @Test
    fun `mapToolResult maps Unit to empty`() = assertEquals(0, mapToolResult(Unit).content.size)

    @Test
    fun `mapToolResult maps null to empty`() = assertEquals(0, mapToolResult(null).content.size)

    @Test
    fun `mapToolResult wraps ContentBlock`() {
        val block = TextContent(text = "x")
        assertEquals(listOf(block), mapToolResult(block).content)
    }

    @Test
    fun `mapToolResult passes List of ContentBlock`() =
        assertEquals(2, mapToolResult(listOf<ContentBlock>(TextContent(text = "a"), TextContent(text = "b"))).content.size)

    @Test
    fun `mapToolResult serializes unknown type as JSON text`() {
        val text = (mapToolResult(TestToolClass.MyInput(listOf("x"), 1)).content.first() as TextContent).text
        assertTrue(text.contains("x"))
    }

    internal class TestToolClass {
        @Serializable
        internal data class MyInput(val list: List<String>, val i: Int)
    }

    internal class NonSuspendTool {
        @Serializable data class Input(val x: Int)
        fun McpCallContext<CallToolRequest>.notSuspend(a: Input): CallToolResult = error("nope")
    }

    internal class WrongReceiverTool {
        @Serializable data class Input(val x: Int)
        suspend fun String.wrongReceiver(a: Input): CallToolResult = error("nope")
    }
}
