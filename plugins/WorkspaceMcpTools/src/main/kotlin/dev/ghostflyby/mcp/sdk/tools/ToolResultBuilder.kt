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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement

/**
 * DSL builder for constructing [CallToolResult] with multiple content blocks.
 *
 * Usage inside a tool function:
 * ```kotlin
 * return callToolResult {
 *     text("## Summary")
 *     text("---")
 *     structured(buildJsonObject { put("score", 95) })
 * }
 * ```
 */
internal class ToolResultBuilder {
    private val contentBlocks = mutableListOf<ContentBlock>()
    private var structuredContent: JsonObject? = null
    private var isError: Boolean = false

    fun text(text: String) {
        contentBlocks.add(TextContent(text = text))
    }

    fun json(json: JsonObject) {
        structuredContent = json
    }

    fun error(message: String) {
        isError = true
        contentBlocks.add(TextContent(text = message))
    }

    internal fun build(): CallToolResult = CallToolResult(
        content = contentBlocks.toList(),
        isError = if (isError) true else null,
        structuredContent = structuredContent,
    )

    companion object
}

internal inline fun <reified T> ToolResultBuilder.structured(block: T) {
    val j = (Json.encodeToJsonElement(block))
    if (j is JsonObject) {
        json(j)
        return
    }
    json(buildJsonObject { put("output", j) })
}

/**
 * Create a [CallToolResult] via the [ToolResultBuilder] DSL.
 */
internal fun McpCallContext<CallToolRequest>.callToolResult(block: ToolResultBuilder.() -> Unit): CallToolResult {
    return ToolResultBuilder().apply(block).build()
}
