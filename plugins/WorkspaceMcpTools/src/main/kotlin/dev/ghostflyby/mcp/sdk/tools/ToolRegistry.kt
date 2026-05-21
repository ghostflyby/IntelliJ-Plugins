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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KType
import kotlin.reflect.full.*
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.typeOf

internal typealias Handler = suspend ClientConnection.(CallToolRequest) -> CallToolResult

private typealias ToolResultMapper = (Any?) -> CallToolResult

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

internal data class ToolInvocationPlan(
    val name: String,
    val argumentDecoders: List<ToolArgumentDecoder>,
    val invocationArgsBuilder: ToolInvocationArgsBuilder,
    val methodHandle: MethodHandle,
    val isSuspend: Boolean,
    val resultMapper: ToolResultMapper,
)

internal data class ToolArgumentDecoder(
    val name: String?,
    val serializer: KSerializer<Any?>,
)

internal fun interface ToolInvocationArgsBuilder {
    fun build(
        conn: ClientConnection,
        request: CallToolRequest,
        decoded: List<Any?>,
    ): Array<Any?>
}

internal fun compileToolInvocationPlan(
    instance: Any,
    func: KFunction<*>,
): ToolInvocationPlan {
    val hasExtensionReceiver = func.extensionReceiverParameter != null
    return ToolInvocationPlan(
        name = func.name,
        argumentDecoders = func.valueParameters.map { param ->
            ToolArgumentDecoder(param.name, serializerForToolType(param.type))
        },
        invocationArgsBuilder = ToolInvocationArgsBuilder { conn, request, decoded ->
            buildToolInvocationArgs(instance, hasExtensionReceiver, conn, request, decoded)
        },
        methodHandle = adaptToolFunctionHandle(func, toolFunctionMethodHandle(func)),
        isSuspend = func.isSuspend,
        resultMapper = toolResultMapper(func.returnType),
    )
}

internal fun buildMethodHandleHandler(plan: ToolInvocationPlan): Handler {
    suspend fun handleRequest(conn: ClientConnection, request: CallToolRequest): CallToolResult {
        val decoded = when (val result = decodeToolArguments(request, plan.name, plan.argumentDecoders)) {
            is ToolArgumentDecodeResult.Decoded -> result.values
            is ToolArgumentDecodeResult.Failed -> return result.result
        }
        val args = plan.invocationArgsBuilder.build(conn, request, decoded)
        val result = if (plan.isSuspend) {
            suspendCoroutineUninterceptedOrReturn { continuation ->
                val callResult = plan.methodHandle.invokeWithArguments(args, continuation)
                if (callResult === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else callResult
            }
        } else {
            withContext(Dispatchers.IO) {
                runInterruptible {
                    plan.methodHandle.invokeWithArguments(args)
                }
            }
        }
        return plan.resultMapper(result)
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
    return decodeToolArguments(
        request = request,
        toolName = info.name,
        argumentDecoders = func.valueParameters.mapIndexed { i, param ->
            ToolArgumentDecoder(param.name, serializerForToolType(info.paramClasses[i]))
        },
    )
}

private fun decodeToolArguments(
    request: CallToolRequest,
    toolName: String,
    argumentDecoders: List<ToolArgumentDecoder>,
): ToolArgumentDecodeResult {
    val jsonArgs: JsonObject = request.params.arguments ?: buildJsonObject { }
    val decoded = try {
        argumentDecoders.map { decoder ->
            val key = jsonArgs.keys.firstOrNull { it == decoder.name } ?: decoder.name
            val element = jsonArgs[key] ?: return ToolArgumentDecodeResult.Failed(
                missingArgumentResult(toolName, decoder.name),
            )
            toolArgsJson.decodeFromJsonElement(
                decoder.serializer,
                element,
            )
        }
    } catch (e: SerializationException) {
        return ToolArgumentDecodeResult.Failed(invalidArgumentsResult(toolName, e.message))
    } catch (e: IllegalArgumentException) {
        return ToolArgumentDecodeResult.Failed(invalidArgumentsResult(toolName, e.message))
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
    return buildToolInvocationArgs(
        instance = instance,
        hasExtensionReceiver = func.extensionReceiverParameter != null,
        conn = conn,
        request = request,
        decoded = decoded,
    ).toList()
}

private fun buildToolInvocationArgs(
    instance: Any,
    hasExtensionReceiver: Boolean,
    conn: ClientConnection,
    request: CallToolRequest,
    decoded: List<Any?>,
): Array<Any?> {
    val mcpCall = McpCallContext(
        WorkspaceMcpCall(
            connection = conn,
            request = request,
            parameters = AncestorContext(emptyMap()),
        ),
    )
    val args = ArrayList<Any?>(decoded.size + if (hasExtensionReceiver) 2 else 1)
    args.add(instance)
    if (hasExtensionReceiver) args.add(mcpCall)
    args.addAll(decoded)
    return args.toTypedArray()
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

private fun toolFunctionMethodHandle(func: KFunction<*>): MethodHandle {
    val method = func.javaMethod
        ?: throw IllegalArgumentException("No Java method for ${func.name}")
    method.isAccessible = true
    val lookup = MethodHandles.privateLookupIn(method.declaringClass, MethodHandles.lookup())
    return lookup.unreflect(method)
}

private fun adaptToolFunctionHandle(func: KFunction<*>, rawHandle: MethodHandle): MethodHandle {
    val filtered = filterValueClassExtensionReceiver(func, rawHandle)
    return if (func.isSuspend) {
        spreadSuspendToolFunctionHandle(filtered)
    } else {
        filtered.asSpreader(toolArgsArrayClass, filtered.type().parameterCount())
            .asType(MethodType.methodType(Any::class.java, toolArgsArrayClass))
    }
}

private fun filterValueClassExtensionReceiver(func: KFunction<*>, handle: MethodHandle): MethodHandle {
    if (func.extensionReceiverParameter == null) return handle
    val extensionReceiverIndex = 1
    val parameterType = handle.type().parameterType(extensionReceiverIndex)
    if (parameterType != WorkspaceMcpCall::class.java) return handle
    return MethodHandles.filterArguments(
        handle,
        extensionReceiverIndex,
        mcpCallContextUnboxHandle(parameterType),
    )
}

private fun spreadSuspendToolFunctionHandle(handle: MethodHandle): MethodHandle {
    val type = handle.type()
    val effectiveParameterCount = type.parameterCount() - 1
    val continuationType = type.parameterType(effectiveParameterCount)
    val continuationFirstType = MethodType.methodType(
        type.returnType(),
        listOf(continuationType) + (0 until effectiveParameterCount).map { type.parameterType(it) },
    )
    val continuationFirst = MethodHandles.permuteArguments(
        handle,
        continuationFirstType,
        *((1..effectiveParameterCount).toList() + 0).toIntArray(),
    )
    val spread = continuationFirst.asSpreader(toolArgsArrayClass, effectiveParameterCount)
    return MethodHandles.permuteArguments(
        spread,
        MethodType.methodType(Any::class.java, toolArgsArrayClass, Continuation::class.java),
        1,
        0,
    )
}

private fun mcpCallContextUnboxHandle(returnType: Class<*>): MethodHandle {
    val method = McpCallContext::class.java.getDeclaredMethod("unbox-impl")
    method.isAccessible = true
    return MethodHandles.lookup().unreflect(method)
        .asType(MethodType.methodType(returnType, McpCallContext::class.java))
}

private val toolArgsArrayClass: Class<Array<Any?>> = Array<Any?>::class.java

private fun serializerForToolType(type: KType): KSerializer<Any?> {
    return serializer(type)
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

private fun toolResultMapper(type: KType): ToolResultMapper {
    val classifier = type.classifier as? KClass<*>
    if (classifier == CallToolResult::class) return { it as CallToolResult }
    if (classifier == JsonObject::class) {
        return { CallToolResult(content = emptyList(), structuredContent = it as JsonObject) }
    }
    if (classifier?.isSubclassOf(ContentBlock::class) == true) {
        return { CallToolResult(content = listOf(it as ContentBlock)) }
    }
    if (classifier == String::class) {
        return { CallToolResult(content = listOf(TextContent(text = it as String))) }
    }
    if (classifier == Unit::class) return { CallToolResult(content = emptyList()) }

    val listArgument = type.arguments.firstOrNull()?.type?.classifier as? KClass<*>
    if (classifier == List::class && listArgument?.isSubclassOf(ContentBlock::class) == true) {
        return { value ->
            val blocks = (value as List<*>).filterIsInstance<ContentBlock>()
            if (blocks.isEmpty()) CallToolResult(content = emptyList())
            else CallToolResult(content = blocks)
        }
    }

    val serializer = serializerForToolType(type)
    return { value ->
        if (value == null) {
            CallToolResult(content = emptyList())
        } else {
            val json = toolArgsJson.encodeToJsonElement(serializer, value)
            if (json is JsonObject) {
                CallToolResult(content = emptyList(), structuredContent = json)
            } else {
                CallToolResult(content = emptyList(), structuredContent = buildJsonObject { put("result", json) })
            }
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
