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
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KType
import kotlin.reflect.full.*
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.typeOf
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

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
        val decoded = when (val result = decodeToolArguments(request, func, info)) {
            is ToolArgumentDecodeResult.Decoded -> result.values
            is ToolArgumentDecodeResult.Failed -> return result.result
        }
        val args = buildToolInvocationArgs(instance, func, conn, request, decoded)
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

internal fun buildMethodHandleHandler(
    instance: Any,
    func: KFunction<*>,
    info: ToolMethodInfo,
): Handler {
    val invoker = MethodHandleToolInvoker.from(func)
    suspend fun handleRequest(conn: ClientConnection, request: CallToolRequest): CallToolResult {
        val decoded = when (val result = decodeToolArguments(request, func, info)) {
            is ToolArgumentDecodeResult.Decoded -> result.values
            is ToolArgumentDecodeResult.Failed -> return result.result
        }
        val args = buildToolInvocationArgs(instance, func, conn, request, decoded)
        val result = invoker.invoke(args)
        return result.toMcpCallToolResult(func.returnType)
    }
    return ::handleRequest
}

private sealed class ToolArgumentDecodeResult {
    data class Decoded(val values: List<Any?>) : ToolArgumentDecodeResult()
    data class Failed(val result: CallToolResult) : ToolArgumentDecodeResult()
}

private fun decodeToolArguments(
    request: CallToolRequest,
    func: KFunction<*>,
    info: ToolMethodInfo,
): ToolArgumentDecodeResult {
    val jsonArgs: JsonObject = request.params.arguments ?: buildJsonObject { }
    val decoded = try {
        func.valueParameters.mapIndexed { i, param ->
            val key = jsonArgs.keys.firstOrNull { it == param.name } ?: param.name
            val element = jsonArgs[key] ?: return ToolArgumentDecodeResult.Failed(
                missingArgumentResult(info.name, param.name),
            )
            toolArgsJson.decodeFromJsonElement(
                serializer(info.paramClasses[i]),
                element,
            )
        }
    } catch (e: SerializationException) {
        return ToolArgumentDecodeResult.Failed(invalidArgumentsResult(info.name, e.message))
    } catch (e: IllegalArgumentException) {
        return ToolArgumentDecodeResult.Failed(invalidArgumentsResult(info.name, e.message))
    }
    return ToolArgumentDecodeResult.Decoded(decoded)
}

private fun buildToolInvocationArgs(
    instance: Any,
    func: KFunction<*>,
    conn: ClientConnection,
    request: CallToolRequest,
    decoded: List<Any?>,
): List<Any?> {
    val mcpCall = McpCallContext(
        WorkspaceMcpCall(
            connection = conn,
            request = request,
            parameters = AncestorContext(emptyMap()),
        ),
    )
    val isExt = func.extensionReceiverParameter != null
    return if (isExt) listOf(instance, mcpCall) + decoded else listOf(instance) + decoded
}

private fun missingArgumentResult(toolName: String, argumentName: String?): CallToolResult =
    CallToolResult(
        content = listOf(TextContent(text = "Missing argument '$argumentName' for $toolName")),
        isError = true,
    )

private fun invalidArgumentsResult(toolName: String, message: String?): CallToolResult =
    CallToolResult(
        content = listOf(TextContent(text = "Invalid arguments for $toolName: $message")),
        isError = true,
    )

private class MethodHandleToolInvoker(
    private val handle: MethodHandle,
    private val isSuspend: Boolean,
    private val parameterTypes: List<Class<*>>,
) {
    suspend fun invoke(args: List<Any?>): Any? {
        val adaptedArgs = adaptValueClassArguments(args)
        return if (isSuspend) {
            suspendCoroutineUninterceptedOrReturn { continuation ->
                val result = handle.invokeWithArguments(adaptedArgs + continuation)
                if (result === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else result
            }
        } else {
            withContext(Dispatchers.IO) {
                runInterruptible {
                    handle.invokeWithArguments(adaptedArgs)
                }
            }
        }
    }

    private fun adaptValueClassArguments(args: List<Any?>): List<Any?> {
        return args.mapIndexed { index, value ->
            val parameterType = parameterTypes[index]
            if (value is McpCallContext<*> && parameterType.isInstance(value.call)) value.call else value
        }
    }

    companion object {
        fun from(func: KFunction<*>): MethodHandleToolInvoker {
            val method = func.javaMethod
                ?: throw IllegalArgumentException("No Java method for ${func.name}")
            method.isAccessible = true
            val lookup = MethodHandles.privateLookupIn(method.declaringClass, MethodHandles.lookup())
            return MethodHandleToolInvoker(
                handle = lookup.unreflect(method),
                isSuspend = func.isSuspend,
                parameterTypes = listOf(method.declaringClass) +
                    method.parameterTypes.dropLast(if (func.isSuspend) 1 else 0),
            )
        }
    }
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
