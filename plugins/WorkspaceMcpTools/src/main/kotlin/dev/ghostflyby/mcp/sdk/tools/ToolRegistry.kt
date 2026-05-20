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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KType
import kotlin.reflect.full.*
import kotlin.reflect.typeOf

internal typealias Handler = suspend ClientConnection.(CallToolRequest) -> CallToolResult

internal enum class ToolRejectReason {
    WRONG_RECEIVER,
    NO_SCHEMA,
}

internal sealed class ToolReflectionResult {
    data class Accepted(val tool: Tool, val handler: Handler) : ToolReflectionResult()
    data class Rejected(val reasons: Set<ToolRejectReason>) : ToolReflectionResult()
}

internal fun <T : Any> reflectTools(
    toolClass: KClass<T>,
): Set<Pair<Tool, Handler>> {
    val instance = toolClass.objectInstance
        ?: toolClass.java.getDeclaredConstructor().newInstance()

    return buildSet {
        for (func in toolClass.declaredMemberFunctions + toolClass.declaredMemberExtensionFunctions) {
            when (val r = reflectOneTool(toolClass, instance, func)) {
                is ToolReflectionResult.Accepted -> add(Pair(r.tool, r.handler))
                is ToolReflectionResult.Rejected -> continue
            }
        }
    }
}

internal fun reflectOneTool(
    toolClass: KClass<*>,
    instance: Any,
    func: KFunction<*>,
): ToolReflectionResult {
    val reasons = ToolMethodInfo.classify(func)
    if (reasons.isNotEmpty()) return ToolReflectionResult.Rejected(reasons)
    val schema = tryResolveFunctionSchema(func.name, toolClass)
    if (schema.isEmpty()) return ToolReflectionResult.Rejected(setOf(ToolRejectReason.NO_SCHEMA))
    val params = schema["parameters"]?.jsonObject
    val tool = Tool(
        name = schema["name"]?.jsonPrimitive?.content ?: func.name,
        description = schema["description"]?.jsonPrimitive?.content ?: "",
        inputSchema = ToolSchema(
            properties = params?.get("properties") as? JsonObject,
            required = (params?.get("required") as? JsonArray)?.map { it.jsonPrimitive.content },
        ),
    )
    val paramClasses = func.valueParameters.map { it.type }
    val info = ToolMethodInfo(func.name, paramClasses)
    return ToolReflectionResult.Accepted(tool, buildHandler(instance, func, info))
}

private fun buildHandler(
    instance: Any,
    func: KFunction<*>,
    info: ToolMethodInfo,
): Handler {
    suspend fun handleRequest(conn: ClientConnection, request: CallToolRequest): CallToolResult {
        val jsonArgs: JsonObject = request.params.arguments ?: buildJsonObject { }
        val decoded = try {
            func.valueParameters.mapIndexed { i, param ->
                val key = jsonArgs.keys.firstOrNull { it == param.name } ?: param.name
                val element = jsonArgs[key] ?: return@handleRequest CallToolResult(
                    content = listOf(TextContent(text = "Missing argument '${param.name}' for ${info.name}")),
                    isError = true,
                )
                toolArgsJson.decodeFromJsonElement(
                    serializer(info.paramClasses[i]),
                    element,
                )
            }
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
        val isExt = func.extensionReceiverParameter != null
        val args = if (isExt) listOf(instance, mcpCall) + decoded else listOf(instance) + decoded
        val result = if (func.isSuspend) {
            func.callSuspend(*args.toTypedArray())
        } else {
            withContext(Dispatchers.IO) {
                runInterruptible {
                    func.call(*args.toTypedArray())
                }
            }
        }
        return result.toMcpCallToolResult(func.returnType)
    }
    return ::handleRequest
}

private fun tryResolveFunctionSchema(funcName: String, toolClass: KClass<*>): JsonObject {
    return try {
        val pkg = toolClass.java.`package`?.name ?: return JsonObject(emptyMap())
        val capName = funcName.replaceFirstChar { it.uppercase() }
        val extFqName = "$pkg.${capName}FunctionSchemaKt"
        val extClass = Class.forName(extFqName)
        val method = extClass.getMethod("${funcName}JsonSchema", KClass::class.java)
        method.invoke(null, toolClass) as JsonObject
    } catch (_: Exception) {
        JsonObject(emptyMap())
    }
}

internal inline fun <reified T> T.toMcpCallToolResult(): CallToolResult {
    return this.toMcpCallToolResult(typeOf<T>())
}

internal fun Any?.toMcpCallToolResult(type: KType): CallToolResult {
    return when (this) {
        is CallToolResult -> this
        is JsonObject -> CallToolResult(content = emptyList(), structuredContent = this)
        is List<*> if (type.arguments[0].type?.classifier as? KClass<*>)?.isSubclassOf(ContentBlock::class) == true -> {
            val blocks = this.filterIsInstance<ContentBlock>()
            if (blocks.isEmpty()) CallToolResult(content = emptyList())
            else CallToolResult(content = blocks)
        }

        is ContentBlock -> CallToolResult(content = listOf(this))
        null, is Unit -> CallToolResult(content = emptyList())
        is String -> CallToolResult(content = listOf(TextContent(text = this)))
        else -> {
            val json = toolArgsJson.encodeToJsonElement(serializer(type), this)
            if (json is JsonObject)
                CallToolResult(content = emptyList(), structuredContent = json) else
                CallToolResult(content = emptyList(), structuredContent = buildJsonObject { put("result", json) })
        }
    }
}

internal class ToolMethodInfo(
    val name: String,
    val paramClasses: List<KType>,
) {
    companion object {
        fun classify(func: KFunction<*>): Set<ToolRejectReason> {
            val reasons = mutableSetOf<ToolRejectReason>()
            func.extensionReceiverParameter?.let { receiver ->
                val c = receiver.type.classifier as? KClass<*>
                if (c != McpCallContext::class) reasons += ToolRejectReason.WRONG_RECEIVER
            }
            return reasons
        }
    }
}
