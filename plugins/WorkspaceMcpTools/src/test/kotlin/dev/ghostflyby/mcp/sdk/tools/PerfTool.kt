/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk.tools

import dev.ghostflyby.mcp.route.McpCallContext
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.schema.Schema

internal class PerfTool {
    @Schema
    suspend fun McpCallContext<CallToolRequest>.do_perf(
        url: String,
        offset: Int,
        limit: Int = 50,
        recursive: Boolean = false,
        tags: List<String>? = null,
    ): CallToolResult {
        val json = buildJsonObject {
            put("url", url)
            put("offset", offset)
            put("effectiveLimit", limit.coerceAtMost(100))
            put("recursive", recursive)
            put("tagCount", tags?.size ?: 0)
        }
        return CallToolResult(content = emptyList(), structuredContent = json)
    }
}
