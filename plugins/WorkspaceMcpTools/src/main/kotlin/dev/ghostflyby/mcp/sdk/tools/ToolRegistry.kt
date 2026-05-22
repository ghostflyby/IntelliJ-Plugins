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
import java.lang.reflect.Method
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.reflect.*
import kotlin.reflect.full.*
import kotlin.reflect.jvm.javaMethod

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
    val optionalParameterNames = func.valueParameters
        .asSequence()
        .filter { it.isOptional }
        .mapNotNull { it.name }
        .toSet()
    val properties = params?.get("properties") as? JsonObject
    val tool = Tool(
        name = schema["name"]?.jsonPrimitive?.content ?: func.name,
        description = schema["description"]?.jsonPrimitive?.content ?: "",
        inputSchema = ToolSchema(
            properties = properties?.unwrapValueClassProperties(func),
            required = (params?.get("required") as? JsonArray)
                ?.map { it.jsonPrimitive.content }
                ?.filterNot { it in optionalParameterNames },
        ),
    )
    val plan = compileToolInvocationPlan(instance, func)
    return ToolReflectionResult.Accepted(tool, buildMethodHandleHandler(plan))
}

internal fun buildKotlinReflectHandler(
    instance: Any,
    func: KFunction<*>,
    info: ToolMethodInfo,
): Handler {
    suspend fun handleRequest(conn: ClientConnection, request: CallToolRequest): CallToolResult {
        val decoded = when (val result = decodeToolArguments(request, func, info)) {
            is ToolArgumentDecodeResult.Decoded -> result.valueByName
            is ToolArgumentDecodeResult.Failed -> return result.result
        }
        val args = buildToolInvocationArgs(instance, func, conn, request, decoded)
        val result = if (func.isSuspend) {
            func.callSuspendBy(args)
        } else {
            withContext(Dispatchers.IO) {
                runInterruptible {
                    func.callBy(args)
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
    val callArgsBuilder: ToolCallArgsBuilder,
    val methodHandle: MethodHandle,
    val isSuspend: Boolean,
    val resultMapper: ToolResultMapper,
)

internal data class ToolArgumentDecoder(
    val name: String?,
    val serializer: KSerializer<Any?>,
    val isOptional: Boolean,
    val valueAdapter: ToolArgumentValueAdapter,
    val defaultPlaceholder: Any?,
    val defaultMaskIndex: Int,
    val defaultMaskBit: Int,
)

internal fun interface ToolArgumentValueAdapter {
    fun adapt(value: Any?): Any?
}

internal fun interface ToolCallArgsBuilder {
    fun build(
        conn: ClientConnection,
        request: CallToolRequest,
    ): ToolCallArgs
}

internal data class ToolCallArgs(
    val values: Array<Any?>,
    val argumentStart: Int,
    val maskStart: Int?,
)

internal fun compileToolInvocationPlan(
    instance: Any,
    func: KFunction<*>,
): ToolInvocationPlan {
    val hasExtensionReceiver = func.extensionReceiverParameter != null
    val valueParameterCount = func.valueParameters.size
    val hasDefaultArguments = func.valueParameters.any { it.isOptional }
    val parameterSlotCount = valueParameterCount + if (hasExtensionReceiver) 1 else 0
    val maskStart = if (hasDefaultArguments) parameterSlotCount else null
    val rawHandle = toolFunctionMethodHandle(func, instance, hasDefaultArguments)
    val jvmValueParameterTypes = jvmValueParameterTypes(
        func = func,
        rawHandle = rawHandle,
        hasExtensionReceiver = hasExtensionReceiver,
        hasDefaultArguments = hasDefaultArguments,
    )
    return ToolInvocationPlan(
        name = func.name,
        argumentDecoders = func.valueParameters.mapIndexed { index, param ->
            ToolArgumentDecoder(
                name = param.name,
                serializer = serializerForToolType(param.type),
                isOptional = param.isOptional,
                valueAdapter = valueAdapterForToolType(param.type, jvmValueParameterTypes[index]),
                defaultPlaceholder = defaultPlaceholderForToolType(param.type),
                defaultMaskIndex = maskStart?.plus(index / 32) ?: -1,
                defaultMaskBit = 1 shl (index % 32),
            )
        },
        callArgsBuilder = { conn, request ->
            newToolCallArgs(hasExtensionReceiver, valueParameterCount, hasDefaultArguments, conn, request)
        },
        methodHandle = adaptToolFunctionHandle(
            func = func,
            rawHandle = rawHandle,
        ),
        isSuspend = func.isSuspend,
        resultMapper = toolResultMapper(func.returnType),
    )
}

internal fun buildMethodHandleHandler(plan: ToolInvocationPlan): Handler {
    suspend fun handleRequest(conn: ClientConnection, request: CallToolRequest): CallToolResult {
        val args = plan.callArgsBuilder.build(conn, request)
        when (val result = decodeToolArguments(request, plan.name, plan.argumentDecoders, args)) {
            is ToolArgumentDecodeResult.Decoded -> Unit
            is ToolArgumentDecodeResult.Failed -> return result.result
        }
        val result = if (plan.isSuspend) {
            suspendCoroutineUninterceptedOrReturn { continuation ->
                val callResult = plan.methodHandle.invokeExact(args.values, continuation)
                if (callResult === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else callResult
            }
        } else {
            withContext(Dispatchers.IO) {
                runInterruptible {
                    plan.methodHandle.invokeExact(args.values)
                }
            }
        }
        return plan.resultMapper(result)
    }
    return ::handleRequest
}

private sealed class ToolArgumentDecodeResult {
    data class Decoded(val valueByName: Map<String?, Any?>) : ToolArgumentDecodeResult()
    data class Failed(val result: CallToolResult) : ToolArgumentDecodeResult()
}

private fun decodeToolArguments(
    request: CallToolRequest,
    func: KFunction<*>,
    info: ToolMethodInfo,
): ToolArgumentDecodeResult {
    val maskStart = -1
    return decodeToolArguments(
        request = request,
        toolName = info.name,
        argumentDecoders = func.valueParameters.mapIndexed { i, param ->
            ToolArgumentDecoder(
                name = param.name,
                serializer = serializerForToolType(info.paramClasses[i]),
                isOptional = param.isOptional,
                valueAdapter = identityToolArgumentValueAdapter,
                defaultPlaceholder = null,
                defaultMaskIndex = maskStart,
                defaultMaskBit = 0,
            )
        },
    )
}

private fun decodeToolArguments(
    request: CallToolRequest,
    toolName: String,
    argumentDecoders: List<ToolArgumentDecoder>,
    target: ToolCallArgs? = null,
): ToolArgumentDecodeResult {
    val jsonArgs: JsonObject = request.params.arguments ?: buildJsonObject { }
    val decodedValues = if (target == null) LinkedHashMap<String?, Any?>(argumentDecoders.size) else null
    try {
        argumentDecoders.forEachIndexed { i, decoder ->
            val key = jsonArgs.keys.firstOrNull { it == decoder.name } ?: decoder.name
            val element = jsonArgs[key]
            if (element == null) {
                if (decoder.isOptional) {
                    target?.values?.set(target.argumentStart + i, decoder.defaultPlaceholder)
                    if (target?.maskStart != null) {
                        val current = target.values[decoder.defaultMaskIndex] as Int
                        target.values[decoder.defaultMaskIndex] = current or decoder.defaultMaskBit
                    }
                    return@forEachIndexed
                }
                return ToolArgumentDecodeResult.Failed(missingArgumentResult(toolName, decoder.name))
            }
            val decoded = toolArgsJson.decodeFromJsonElement(
                decoder.serializer,
                element,
            )
            val adapted = decoder.valueAdapter.adapt(decoded)
            if (target != null) target.values[target.argumentStart + i] = adapted
            else decodedValues?.put(decoder.name, decoded)
        }
    } catch (e: SerializationException) {
        return ToolArgumentDecodeResult.Failed(invalidArgumentsResult(toolName, e.message))
    } catch (e: IllegalArgumentException) {
        return ToolArgumentDecodeResult.Failed(invalidArgumentsResult(toolName, e.message))
    }
    return ToolArgumentDecodeResult.Decoded(decodedValues ?: emptyMap())
}

private fun buildToolInvocationArgs(
    instance: Any,
    func: KFunction<*>,
    conn: ClientConnection,
    request: CallToolRequest,
    decoded: Map<String?, Any?>,
): Map<KParameter, Any?> {
    val hasExtensionReceiver = func.extensionReceiverParameter != null
    val args = LinkedHashMap<KParameter, Any?>(func.parameters.size)
    func.instanceParameter?.let { args[it] = instance }
    if (hasExtensionReceiver) {
        func.extensionReceiverParameter?.let { args[it] = McpCallContext(newWorkspaceMcpCall(conn, request)) }
    }
    for (param in func.valueParameters) {
        if (decoded.containsKey(param.name)) args[param] = decoded[param.name]
    }
    return args
}

private fun newToolCallArgs(
    hasExtensionReceiver: Boolean,
    valueParameterCount: Int,
    hasDefaultArguments: Boolean,
    conn: ClientConnection,
    request: CallToolRequest,
): ToolCallArgs {
    val receiverCount = if (hasExtensionReceiver) 1 else 0
    val parameterSlotCount = valueParameterCount + receiverCount
    val maskCount = if (hasDefaultArguments) ((valueParameterCount + 31) / 32) else 0
    val args = arrayOfNulls<Any>(parameterSlotCount + maskCount + if (hasDefaultArguments) 1 else 0)
    if (hasExtensionReceiver) args[0] = newWorkspaceMcpCall(conn, request)
    val maskStart = if (hasDefaultArguments) parameterSlotCount else null
    if (maskStart != null) {
        repeat(maskCount) { args[maskStart + it] = 0 }
        args[maskStart + maskCount] = null
    }
    return ToolCallArgs(
        values = args,
        argumentStart = receiverCount,
        maskStart = maskStart,
    )
}

private fun newWorkspaceMcpCall(
    conn: ClientConnection,
    request: CallToolRequest,
): WorkspaceMcpCall<CallToolRequest> {
    return WorkspaceMcpCall(
        connection = conn,
        request = request,
        parameters = AncestorContext(emptyMap()),
    )
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

private fun toolFunctionMethodHandle(
    func: KFunction<*>,
    instance: Any,
    hasDefaultArguments: Boolean,
): MethodHandle {
    val method = if (hasDefaultArguments) {
        defaultMethodFor(func)
    } else {
        func.javaMethod ?: throw IllegalArgumentException("No Java method for ${func.name}")
    }
    method.isAccessible = true
    val lookup = MethodHandles.privateLookupIn(method.declaringClass, MethodHandles.lookup())
    return lookup.unreflect(method).bindTo(instance)
}

private fun defaultMethodFor(func: KFunction<*>): Method {
    val method = func.javaMethod
        ?: throw IllegalArgumentException("No Java method for ${func.name}")
    val defaultMethodName = "${method.name}\$default"
    val expectedValueParameterCount = method.parameterCount + ((func.valueParameters.size + 31) / 32) + 2
    return method.declaringClass.declaredMethods.singleOrNull {
        it.name == defaultMethodName && it.parameterCount == expectedValueParameterCount
    }
        ?: throw IllegalArgumentException("No default Java method for ${func.name}")
}

private fun adaptToolFunctionHandle(
    func: KFunction<*>,
    rawHandle: MethodHandle,
): MethodHandle {
    return if (func.isSuspend) {
        spreadSuspendToolFunctionHandle(rawHandle)
    } else {
        rawHandle.asSpreader(toolArgsArrayClass, rawHandle.type().parameterCount())
            .asType(MethodType.methodType(Any::class.java, toolArgsArrayClass))
    }
}

private fun spreadSuspendToolFunctionHandle(handle: MethodHandle): MethodHandle {
    val type = handle.type()
    val continuationIndex = (0 until type.parameterCount())
        .single { Continuation::class.java.isAssignableFrom(type.parameterType(it)) }
    val effectiveParameterCount = type.parameterCount() - 1
    val continuationType = type.parameterType(continuationIndex)
    val nonContinuationParameterTypes = (0 until type.parameterCount())
        .filter { it != continuationIndex }
        .map { type.parameterType(it) }
    val continuationFirstType = MethodType.methodType(
        type.returnType(),
        listOf(continuationType) + nonContinuationParameterTypes,
    )
    var nonContinuationIndex = 1
    val continuationFirst = MethodHandles.permuteArguments(
        handle,
        continuationFirstType,
        *(0 until type.parameterCount()).map { index ->
            if (index == continuationIndex) 0 else nonContinuationIndex++
        }.toIntArray(),
    )
    val spread = continuationFirst.asSpreader(toolArgsArrayClass, effectiveParameterCount)
    return MethodHandles.permuteArguments(
        spread,
        MethodType.methodType(Any::class.java, toolArgsArrayClass, Continuation::class.java),
        1,
        0,
    )
}

private val toolArgsArrayClass: Class<Array<Any?>> = Array<Any?>::class.java

private val identityToolArgumentValueAdapter = ToolArgumentValueAdapter { it }

private fun serializerForToolType(type: KType): KSerializer<Any?> {
    return serializer(type)
}

private fun jvmValueParameterTypes(
    func: KFunction<*>,
    rawHandle: MethodHandle,
    hasExtensionReceiver: Boolean,
    hasDefaultArguments: Boolean,
): List<Class<*>> {
    val valueParameterStart = if (hasExtensionReceiver) 1 else 0
    val valueParameterCount = func.valueParameters.size
    val rawType = rawHandle.type()
    if (!hasDefaultArguments || !func.isSuspend) {
        return (valueParameterStart until valueParameterStart + valueParameterCount)
            .map { rawType.parameterType(it) }
    }
    val continuationIndex = (0 until rawType.parameterCount())
        .single { Continuation::class.java.isAssignableFrom(rawType.parameterType(it)) }
    return (valueParameterStart until valueParameterStart + valueParameterCount)
        .map { index ->
            val rawIndex = if (index < continuationIndex) index else index + 1
            rawType.parameterType(rawIndex)
        }
}

private fun valueAdapterForToolType(type: KType, jvmParameterType: Class<*>): ToolArgumentValueAdapter {
    val valueClass = type.classifier as? KClass<*> ?: return identityToolArgumentValueAdapter
    if (!valueClass.isValue) return identityToolArgumentValueAdapter
    if (jvmParameterType == valueClass.java) return identityToolArgumentValueAdapter
    val unboxMethod = valueClass.java.declaredMethods.singleOrNull { it.name == "unbox-impl" }
        ?: return identityToolArgumentValueAdapter
    if (jvmParameterType != unboxMethod.returnType) return identityToolArgumentValueAdapter
    unboxMethod.isAccessible = true
    return ToolArgumentValueAdapter { value ->
        if (value == null) null else unboxMethod.invoke(value)
    }
}

private fun JsonObject.unwrapValueClassProperties(func: KFunction<*>): JsonObject {
    val replacements = func.valueParameters.mapNotNull { param ->
        val name = param.name ?: return@mapNotNull null
        val valueClass = param.type.classifier as? KClass<*> ?: return@mapNotNull null
        if (!valueClass.isValue) return@mapNotNull null
        val wrappedSchema = get(name)?.jsonObject ?: return@mapNotNull null
        val propertySchemas = wrappedSchema["properties"]?.jsonObject ?: return@mapNotNull null
        val underlyingSchema = propertySchemas.values.singleOrNull()?.jsonObject ?: return@mapNotNull null
        val replacement = if (wrappedSchema.isNullableSchema()) underlyingSchema.withNullType() else underlyingSchema
        name to replacement
    }.toMap()
    if (replacements.isEmpty()) return this
    return JsonObject(mapValues { (name, schema) -> replacements[name] ?: schema })
}

private fun JsonObject.isNullableSchema(): Boolean {
    return when (val type = get("type")) {
        is JsonArray -> type.any { it.jsonPrimitive.content == "null" }
        else -> type?.jsonPrimitive?.content == "null"
    }
}

private fun JsonObject.withNullType(): JsonObject {
    val existingTypes = when (val type = get("type")) {
        is JsonArray -> type.map { it.jsonPrimitive.content }
        null -> emptyList()
        else -> listOf(type.jsonPrimitive.content)
    }
    if ("null" in existingTypes) return this
    return JsonObject(
        this + ("type" to JsonArray((existingTypes + "null").map { JsonPrimitive(it) })),
    )
}

private fun defaultPlaceholderForToolType(type: KType): Any? {
    val classifier = type.classifier as? KClass<*> ?: return null
    when (classifier) {
        Boolean::class -> return false
        Byte::class -> return 0.toByte()
        Char::class -> return 0.toChar()
        Double::class -> return 0.0
        Float::class -> return 0f
        Int::class -> return 0
        Long::class -> return 0L
        Short::class -> return 0.toShort()
    }
    val valueClass = if (classifier.isValue) classifier.java.declaredMethods
        .singleOrNull { it.name == "unbox-impl" }
        ?.returnType else classifier.javaObjectType
    return when (valueClass) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        Character.TYPE -> 0.toChar()
        java.lang.Double.TYPE -> 0.0
        java.lang.Float.TYPE -> 0f
        Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Short.TYPE -> 0.toShort()
        else -> null
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
