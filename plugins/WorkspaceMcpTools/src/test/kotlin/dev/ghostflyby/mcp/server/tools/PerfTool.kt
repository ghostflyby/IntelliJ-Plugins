/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.server.tools

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.schema.Schema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class PerfTool {
    @Schema
    fun do_perf(
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
