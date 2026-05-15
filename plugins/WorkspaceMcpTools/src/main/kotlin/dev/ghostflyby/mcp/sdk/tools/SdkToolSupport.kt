/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * This file is part of IntelliJ-Plugins by ghostflyby
 *
 * IntelliJ-Plugins by ghostflyby is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see
 * <https://www.gnu.org/licenses/>.
 */

package dev.ghostflyby.mcp.sdk.tools

import dev.ghostflyby.mcp.document.tools.jsonSchema
import dev.ghostflyby.mcp.sdk.WorkspaceMcpRequestRunner
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer

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

internal inline fun <reified T : Any> sdkToolDescriptor(
    name: String,
    description: String,
    title: String? = null,
    inputSchema: ToolSchema = schemaFor<T>(),
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

internal class SdkToolHandlerContext(
    val runner: WorkspaceMcpRequestRunner,
    val sessionId: String?,
)

internal interface WorkspaceMcpProjectToolArguments {
    val projectKey: String?
    val projectPath: String?
}

internal val toolArgsJson: Json = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
}

internal inline fun <reified T : Any> schemaFor(): ToolSchema {
    val jsonObj = T::class.jsonSchema  // KSP-generated extension property
    return ToolSchema(
        schema = jsonObj["$schema"]?.jsonPrimitive?.content,
        properties = jsonObj["properties"]?.jsonObject
            ?: (jsonObj["$defs"]?.jsonObject?.get(T::class.simpleName!!)?.jsonObject?.get("properties")?.jsonObject),
        required = jsonObj["required"]?.jsonArray?.map { it.jsonPrimitive.content },
        defs = null,
    )
}



