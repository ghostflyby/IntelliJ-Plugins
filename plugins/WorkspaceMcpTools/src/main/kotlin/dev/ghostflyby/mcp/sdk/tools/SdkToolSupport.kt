/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk.tools

import dev.ghostflyby.mcp.sdk.WorkspaceMcpRequestRunner
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonObject

/**
 * Registers a tool on the MCP SDK server using [SdkToolDescriptor].
 * The [runner] is injected at registration time so tool handlers
 * can use [WorkspaceMcpRequestRunner] for project resolution.
 */
internal fun Server.registerSdkTool(
    descriptor: SdkToolDescriptor,
    runner: WorkspaceMcpRequestRunner,
) {
    addTool(
        name = descriptor.name,
        description = descriptor.description,
        inputSchema = descriptor.inputSchema,
        title = descriptor.title,
        outputSchema = descriptor.outputSchema,
        toolAnnotations = descriptor.toolAnnotations,
        meta = descriptor.meta,
        execution = null,
    ) { request ->
        val args = parseToolArguments(request.params.arguments)
        val ctx = SdkToolHandlerContext(runner = runner, sessionId = this.sessionId)
        descriptor.handler(ctx, args)
    }
}

/**
 * Descriptor for an MCP SDK tool that uses the [WorkspaceMcpRequestRunner].
 *
 * Handlers receive the [SdkToolHandlerContext] and a parsed map of
 * arguments from the [CallToolRequest] and must return a [CallToolResult].
 */
internal class SdkToolDescriptor(
    val name: String,
    val description: String,
    val title: String? = null,
    val inputSchema: ToolSchema = ToolSchema(),
    val outputSchema: ToolSchema? = null,
    val toolAnnotations: ToolAnnotations? = null,
    val meta: JsonObject? = null,
    val handler: suspend SdkToolHandlerContext.(args: Map<String, String>) -> CallToolResult,
)

/**
 * Handler context for SDK tool execution, providing access to
 * the request runner and session ID.
 */
internal class SdkToolHandlerContext(
    val runner: WorkspaceMcpRequestRunner,
    val sessionId: String?,
)

/**
 * Parse tool arguments from a nullable [JsonObject] into a [Map<String, String>].
 * Non-string values are coerced to their JSON representation; nulls and blanks are skipped.
 */
internal fun parseToolArguments(args: JsonObject?): Map<String, String> {
    if (args == null) return emptyMap()
    return args.entries.mapNotNull { (key, value) ->
        val str = when (value) {
            is JsonPrimitive -> value.content
            is JsonArray -> value.toString()
            else -> value.toString()
        }
        if (str.isBlank()) null else key to str
    }.toMap()
}

/**
 * Build a [ToolSchema] with string properties and optional required list.
 */
internal fun toolSchema(
    properties: Map<String, String>,
    required: List<String> = emptyList(),
    additionalProperties: Boolean = false,
): ToolSchema {
    val propsObj = buildJsonObject {
        properties.forEach { (name, desc) ->
            putJsonObject(name) {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive(desc))
            }
        }
    }
    return ToolSchema(
        schema = null,
        properties = propsObj,
        required = required.ifEmpty { null },
        defs = null,
    )
}
