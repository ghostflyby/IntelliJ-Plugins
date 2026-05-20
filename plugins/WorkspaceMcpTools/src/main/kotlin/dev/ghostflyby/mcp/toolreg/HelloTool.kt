/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.toolreg

import dev.ghostflyby.mcp.route.McpCallContext
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.Serializable
import kotlinx.schema.Schema

internal class HelloTool {
    @Schema
    @Serializable
    internal data class MyInput(val list: List<String>, val i: Int)

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.someTool(a: MyInput): CallToolResult {
        return CallToolResult(
            content = listOf(
                TextContent(text = "list: ${a.list}, i: ${a.i}"),
            ),
        )
    }
}
