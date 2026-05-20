/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk.tools

import dev.ghostflyby.mcp.route.AncestorContext
import dev.ghostflyby.mcp.route.McpCallContext
import dev.ghostflyby.mcp.route.WorkspaceMcpCall
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.instanceParameter
import kotlin.reflect.KParameter
import kotlin.reflect.full.valueParameters

internal typealias Handler = suspend ClientConnection.(CallToolRequest) -> CallToolResult

internal fun <T : Any> reflectTools(
    toolClass: KClass<T>,
): Set<Pair<Tool, Handler>> {
    val instance = toolClass.objectInstance
        ?: toolClass.java.getDeclaredConstructor().newInstance()

    return buildSet {
        for (func in toolClass.declaredMemberFunctions) {
            val info = ToolMethodInfo.from(func) ?: continue
            val schema = tryResolveKspSchema(info.paramClass)
            val tool = Tool(
                name = info.name,
                inputSchema = ToolSchema(
                    properties = schema["properties"] as? JsonObject,
                    required = (schema["required"] as? JsonArray)?.map { it.jsonPrimitive.content },
                ),
            )
            add(Pair(tool, buildHandler(instance, func, info)))
        }
    }
}

private fun <T : Any> buildHandler(
    instance: T,
    func: KFunction<*>,
    info: ToolMethodInfo,
): Handler {
    suspend fun handleRequest(conn: ClientConnection, request: CallToolRequest): CallToolResult {
        val jsonArgs: JsonObject = request.params.arguments ?: buildJsonObject { }
        val decoded: Any = try {
            toolArgsJson.decodeFromJsonElement(serializer(info.paramClass.java), jsonArgs)
        } catch (e: SerializationException) {
            return CallToolResult(
                content = listOf(TextContent(text = "Invalid arguments for ${info.name}: ${e.message}")),
                isError = true,
            )
        } catch (e: IllegalArgumentException) {
            return CallToolResult(
                content = listOf(TextContent(text = "Invalid arguments for ${info.name}: ${e.message}")),
                isError = true,
            )
        }

        val mcpCall = McpCallContext(
            WorkspaceMcpCall(
                connection = conn,
                request = request,
                parameters = AncestorContext(emptyMap()),
            ),
        )
        val result = func.callSuspend(instance, mcpCall, decoded)
        return mapToolResult(result)
    }
    return ::handleRequest
}

private fun tryResolveKspSchema(paramClass: KClass<*>): JsonObject {
    return try {
        val pkg = paramClass.java.`package`?.name ?: return JsonObject(emptyMap())
        val simpleName = paramClass.simpleName ?: return JsonObject(emptyMap())
        val extFqName = "$pkg.${simpleName}SchemaExtensionsKt"
        val extClass = Class.forName(extFqName)
        val method = extClass.getMethod("getJsonSchema", KClass::class.java)
        method.invoke(null, paramClass) as JsonObject
    } catch (_: Exception) {
        JsonObject(emptyMap())
    }
}

internal fun mapToolResult(result: Any?): CallToolResult {
    return when (result) {
        is CallToolResult -> result
        is List<*> -> {
            val blocks = result.mapNotNull { it as? ContentBlock }
            if (blocks.isEmpty()) CallToolResult(content = emptyList())
            else CallToolResult(content = blocks)
        }
        is ContentBlock -> CallToolResult(content = listOf(result))
        null, is Unit -> CallToolResult(content = emptyList())
        is String -> CallToolResult(content = listOf(TextContent(text = result)))
        else -> {
            val json = toolArgsJson.encodeToString(serializer(result::class.java), result)
            CallToolResult(content = listOf(TextContent(text = json)))
        }
    }
}

private class ToolMethodInfo private constructor(
    val name: String,
    val paramClass: KClass<*>,
) {
    companion object {
        fun from(func: KFunction<*>): ToolMethodInfo? {
            if (!func.isSuspend) return null
            val valueParams = func.valueParameters
            if (valueParams.size != 1) return null
            // instanceParameter works for top-level extensions;
            // For member extensions, find receiver in parameters (INSTANCE or EXTENSION_RECEIVER kind).
            val receiver = func.instanceParameter
                ?: func.parameters.firstOrNull { p ->
                    p.kind == KParameter.Kind.EXTENSION_RECEIVER || p.kind == KParameter.Kind.INSTANCE
                }
                ?: return null
            val receiverClassifier = receiver.type.classifier as? KClass<*> ?: return null
            if (receiverClassifier == McpCallContext::class) {
                val paramClass = (valueParams.first().type.classifier as? KClass<*>) ?: return null
                return ToolMethodInfo(name = func.name, paramClass = paramClass)
            }
            return null
            val paramClass = (valueParams.first().type.classifier as? KClass<*>) ?: return null
            return ToolMethodInfo(name = func.name, paramClass = paramClass)
        }
    }
}
