/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk.tools

import dev.ghostflyby.mcp.route.AncestorContext
import dev.ghostflyby.mcp.route.McpCallContext
import dev.ghostflyby.mcp.route.WorkspaceMcpCall
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolver
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
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
import kotlin.reflect.full.valueParameters

internal fun <T : Any> registerToolClass(
    server: Server,
    toolClass: KClass<T>,
    projectResolver: WorkspaceProjectResolver,
    trackedTools: MutableSet<String>,
) {
    val instance = toolClass.objectInstance
        ?: toolClass.java.getDeclaredConstructor().newInstance()

    for (func in toolClass.declaredMemberFunctions) {
        val pinfo = ToolMethodInfo.from(func) ?: continue
        registerMethod(server, instance, func, pinfo, projectResolver, trackedTools)
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
            val receiver = func.instanceParameter ?: return null
            val receiverClassifier = receiver.type.classifier as? KClass<*> ?: return null
            if (receiverClassifier != McpCallContext::class) return null
            val paramClass = (valueParams.first().type.classifier as? KClass<*>) ?: return null
            return ToolMethodInfo(name = func.name, paramClass = paramClass)
        }
    }
}

private fun <T : Any> registerMethod(
    server: Server,
    instance: T,
    func: KFunction<*>,
    info: ToolMethodInfo,
    projectResolver: WorkspaceProjectResolver,
    trackedTools: MutableSet<String>,
) {
    val schema = tryResolveKspSchema(info.paramClass)
    val name = info.name

    server.addTool(
        name = name,
        description = "",
        inputSchema = ToolSchema(
            properties = schema["properties"] as? JsonObject,
            required = (schema["required"] as? JsonArray)?.map { it.jsonPrimitive.content },
        ),
    ) { request ->
        val jsonArgs: JsonObject = request.params.arguments ?: buildJsonObject { }
        val decoded: Any = try {
            toolArgsJson.decodeFromJsonElement(
                serializer(info.paramClass.java),
                jsonArgs,
            )
        } catch (e: SerializationException) {
            return@addTool CallToolResult(
                content = listOf(TextContent(text = "Invalid arguments for $name: ${e.message}")),
                isError = true,
            )
        } catch (e: IllegalArgumentException) {
            return@addTool CallToolResult(
                content = listOf(TextContent(text = "Invalid arguments for $name: ${e.message}")),
                isError = true,
            )
        }

        val mcpCall = McpCallContext(
            WorkspaceMcpCall(
                connection = this,
                request = request,
                parameters = AncestorContext(emptyMap()),
                projectResolver = projectResolver,
            ),
        )

        val result = func.callSuspend(instance, mcpCall, decoded)
        mapToolResult(result)
    }
    trackedTools.add(name)
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

private fun mapToolResult(result: Any?): CallToolResult {
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
