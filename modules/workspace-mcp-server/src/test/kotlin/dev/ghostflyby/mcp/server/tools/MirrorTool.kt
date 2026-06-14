/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.server.tools

import dev.ghostflyby.mcp.server.route.McpCallContext
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class MirrorTool {
    suspend fun McpCallContext<CallToolRequest>.do_perf(
        url: String,
        offset: Int,
        limit: Int,
        recursive: Boolean,
        tags: List<String>,
    ): CallToolResult {
        return CallToolResult(
            content = emptyList(),
            structuredContent = buildJsonObject {
                put("url", url)
            },
        )
    }
}
