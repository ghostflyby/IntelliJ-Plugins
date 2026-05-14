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
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Registers a tool on the MCP SDK server using [SdkToolDescriptor].
 * Arguments are decoded from the [CallToolRequest] using kotlinx.serialization
 * into the typed [T] DTO.
 *
 * On decoding failure the call returns [CallToolResult] with [isError] = true
 * and a concise message; the protocol-level request does not fail.
 */
internal fun <T : Any> Server.registerSdkTool(
    descriptor: SdkToolDescriptor<T>,
    runner: WorkspaceMcpRequestRunner,
    json: Json = toolArgsJson,
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
        val jsonArgs: JsonObject = request.params.arguments ?: buildJsonObject { }
        val decoded: T = try {
            json.decodeFromJsonElement(descriptor.serializer, jsonArgs)
        } catch (e: SerializationException) {
            return@addTool CallToolResult(
                content = listOf(TextContent(
                    text = "Invalid arguments for ${descriptor.name}: ${e.message}",
                )),
                isError = true,
            )
        } catch (e: IllegalArgumentException) {
            return@addTool CallToolResult(
                content = listOf(TextContent(
                    text = "Invalid arguments for ${descriptor.name}: ${e.message}",
                )),
                isError = true,
            )
        }
        val ctx = SdkToolHandlerContext(runner = runner, sessionId = this.sessionId)
        descriptor.handler(ctx, decoded)
    }
}

/**
 * Descriptor for an MCP SDK tool that uses the [WorkspaceMcpRequestRunner].
 *
 * Handlers receive the [SdkToolHandlerContext] and a typed [T] decoded from
 * [CallToolRequest] arguments via kotlinx.serialization.
 */
internal class SdkToolDescriptor<T : Any>(
    val name: String,
    val description: String,
    val serializer: KSerializer<T>,
    val title: String? = null,
    val inputSchema: ToolSchema = ToolSchema(),
    val outputSchema: ToolSchema? = null,
    val toolAnnotations: ToolAnnotations? = null,
    val meta: JsonObject? = null,
    val handler: suspend SdkToolHandlerContext.(args: T) -> CallToolResult,
)

/**
 * Builds an [SdkToolDescriptor] with automatic serializer resolution for [T].
 */
internal inline fun <reified T : Any> sdkToolDescriptor(
    name: String,
    description: String,
    title: String? = null,
    inputSchema: ToolSchema = ToolSchema(),
    outputSchema: ToolSchema? = null,
    toolAnnotations: ToolAnnotations? = null,
    meta: JsonObject? = null,
    noinline handler: suspend SdkToolHandlerContext.(args: T) -> CallToolResult,
): SdkToolDescriptor<T> = SdkToolDescriptor(
    name = name,
    description = description,
    serializer = serializer<T>(),
    title = title,
    outputSchema = outputSchema,
    inputSchema = inputSchema,
    toolAnnotations = toolAnnotations,
    meta = meta,
    handler = handler,
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
 * Shared interface for SDK tool argument DTOs that carry optional
 * project resolution hints. Tools that need project resolution
 * should implement this interface and use the overload of
 * [WorkspaceMcpRequestRunner.callToolWithProject] that accepts it.
 */
internal interface WorkspaceMcpProjectToolArguments {
    val projectKey: String?
    val projectPath: String?
}

/**
 * Shared JSON configuration for decoding tool arguments.
 * Fails on unknown keys to catch agent-side schema drift early.
 */
internal val toolArgsJson: Json = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
}

/**
 * JSON Schema primitive type names accepted by MCP tool input schemas.
 */
internal enum class SdkToolJsonType(val jsonName: String) {
    String("string"),
    Boolean("boolean"),
    Integer("integer"),
    Number("number"),
    Object("object"),
    Array("array"),
}

/**
 * JSON Schema property declaration for an SDK tool input field.
 */
internal data class SdkToolProperty(
    val type: SdkToolJsonType,
    val description: String,
)

internal fun sdkStringProperty(description: String): SdkToolProperty =
    SdkToolProperty(type = SdkToolJsonType.String, description = description)

internal fun sdkBooleanProperty(description: String): SdkToolProperty =
    SdkToolProperty(type = SdkToolJsonType.Boolean, description = description)

internal fun sdkIntegerProperty(description: String): SdkToolProperty =
    SdkToolProperty(type = SdkToolJsonType.Integer, description = description)

internal fun sdkObjectProperty(description: String): SdkToolProperty =
    SdkToolProperty(type = SdkToolJsonType.Object, description = description)

internal fun sdkArrayProperty(description: String): SdkToolProperty =
    SdkToolProperty(type = SdkToolJsonType.Array, description = description)

/**
 * Build a [ToolSchema] with explicitly typed properties and optional required list.
 */
internal fun toolSchema(
    properties: Map<String, SdkToolProperty>,
    required: List<String> = emptyList(),
): ToolSchema {
    val propsObj = buildJsonObject {
        properties.forEach { (name, property) ->
            putJsonObject(name) {
                put("type", JsonPrimitive(property.type.jsonName))
                put("description", JsonPrimitive(property.description))
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
