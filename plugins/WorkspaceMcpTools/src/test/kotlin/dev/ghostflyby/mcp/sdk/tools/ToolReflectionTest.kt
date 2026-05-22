/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk.tools

import dev.ghostflyby.mcp.route.McpCallContext
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.schema.Schema
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredMemberExtensionFunctions
import kotlin.reflect.full.declaredMemberFunctions

internal class ToolReflectionTest {

    private fun reflectFirst(toolClass: KClass<*>): ToolReflectionResult {
        val allFuncs = toolClass.declaredMemberFunctions + toolClass.declaredMemberExtensionFunctions
        val func = allFuncs.first()
        val instance = toolClass.java.getDeclaredConstructor().newInstance()
        return reflectOneTool(toolClass, instance, func)
    }

    @Test
    fun `valid tool accepted`() {
        assertTrue(reflectFirst(ValidTool::class) is ToolReflectionResult.Accepted)
    }

    @Test
    fun `non-suspend accepted`() {
        assertTrue(reflectFirst(NonSuspendTool::class) is ToolReflectionResult.Accepted)
    }

    @Test
    fun `regular member accepted`() {
        assertTrue(reflectFirst(NoReceiverTool::class) is ToolReflectionResult.Accepted)
    }

    @Test
    fun `wrong receiver rejected`() {
        val r = reflectFirst(WrongReceiverTool::class) as ToolReflectionResult.Rejected
        assertEquals(setOf(ToolRejectReason.WRONG_RECEIVER), r.reasons)
    }

    @Test
    fun `no schema rejected`() {
        val r = reflectFirst(NoSchemaTool::class) as ToolReflectionResult.Rejected
        assertEquals(setOf(ToolRejectReason.NO_SCHEMA), r.reasons)
    }

    @Test fun `multi param accepted`() {
        assertTrue(reflectFirst(MultiParamTool::class) is ToolReflectionResult.Accepted)
    }

    @Test fun `zero param accepted`() {
        assertTrue(reflectFirst(ZeroParamTool::class) is ToolReflectionResult.Accepted)
    }

    @Test
    fun `default params accepted`() {
        assertTrue(reflectFirst(DefaultParamTool::class) is ToolReflectionResult.Accepted)
    }

    @Test
    fun `default params are not required in tool schema`() {
        val r = reflectFirst(DefaultParamTool::class) as ToolReflectionResult.Accepted
        assertEquals(listOf("prefix", "suffix"), r.tool.inputSchema.required)
    }

    @Test
    fun `value class accepted`() {
        assertTrue(reflectFirst(ValueClassTool::class) is ToolReflectionResult.Accepted)
    }

    @Test
    fun `value class uses underlying property schema`() {
        val r = reflectFirst(ValueClassTool::class) as ToolReflectionResult.Accepted
        val pathSchema = r.tool.inputSchema.properties?.get("path")?.jsonObject
        assertEquals("string", pathSchema?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun `nullable value class accepted`() {
        assertTrue(reflectFirst(NullableValueClassTool::class) is ToolReflectionResult.Accepted)
    }

    @Test
    fun `nullable value class schema allows underlying value or null`() {
        val r = reflectFirst(NullableValueClassTool::class) as ToolReflectionResult.Accepted
        val pathSchema = r.tool.inputSchema.properties?.get("path")?.jsonObject
        assertEquals(listOf("string", "null"), pathSchema?.typeNames())
    }

    @Test
    fun `primitive value class accepted`() {
        assertTrue(reflectFirst(PrimitiveValueClassTool::class) is ToolReflectionResult.Accepted)
    }

    @Test
    fun `primitive value class uses underlying property schema`() {
        val r = reflectFirst(PrimitiveValueClassTool::class) as ToolReflectionResult.Accepted
        val idSchema = r.tool.inputSchema.properties?.get("id")?.jsonObject
        assertEquals("integer", idSchema?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun `nullable primitive value class accepted`() {
        assertTrue(reflectFirst(NullablePrimitiveValueClassTool::class) is ToolReflectionResult.Accepted)
    }

    @Test
    fun `nullable primitive value class schema allows underlying value or null`() {
        val r = reflectFirst(NullablePrimitiveValueClassTool::class) as ToolReflectionResult.Accepted
        val idSchema = r.tool.inputSchema.properties?.get("id")?.jsonObject
        assertEquals(listOf("integer", "null"), idSchema?.typeNames())
    }

    @Test
    fun `nullable underlying value class accepted`() {
        assertTrue(reflectFirst(NullableUnderlyingValueClassTool::class) is ToolReflectionResult.Accepted)
    }

    @Test
    fun `nullable underlying value class schema allows underlying value or null`() {
        val r = reflectFirst(NullableUnderlyingValueClassTool::class) as ToolReflectionResult.Accepted
        val pathSchema = r.tool.inputSchema.properties?.get("path")?.jsonObject
        assertEquals(listOf("string", "null"), pathSchema?.typeNames())
    }
}

private fun JsonObject.typeNames(): List<String> {
    return when (val type = get("type")) {
        is JsonArray -> type.map { it.jsonPrimitive.content }
        else -> listOfNotNull(type?.jsonPrimitive?.content)
    }
}

internal class ValidTool {
    @Schema
    @Serializable
    data class VaIn(val x: Int)

    @Schema
    suspend fun McpCallContext<CallToolRequest>.do_thing(a: VaIn): CallToolResult =
        CallToolResult(content = listOf(TextContent(text = "ok")))
}

internal class NoSchemaTool {
    @Serializable
    data class NsIn(val x: Int)

    // no @Schema on function
    suspend fun McpCallContext<CallToolRequest>.noSchema(a: NsIn): CallToolResult =
        CallToolResult(content = listOf(TextContent(text = "ok")))
}

internal class NonSuspendTool {
    @Serializable
    data class NtIn(val x: Int)

    @Schema
    fun McpCallContext<CallToolRequest>.not_suspend(a: NtIn): CallToolResult =
        CallToolResult(content = listOf(TextContent(text = "ok")))
}

internal class WrongReceiverTool {
    @Serializable
    data class WrIn(val x: Int)

    @Schema
    suspend fun String.wrongReceiver(a: WrIn): CallToolResult =
        CallToolResult(content = listOf(TextContent(text = "ok")))
}

internal class NoReceiverTool {
    @Schema
    @Serializable
    data class NrIn(val x: Int)

    @Schema
    suspend fun no_receiver(a: NrIn): CallToolResult =
        CallToolResult(content = listOf(TextContent(text = "ok")))
}
internal class MultiParamTool {
    @Serializable data class A(val x: Int)
    @Serializable
    data class B(val y: String)
    @Schema
    suspend fun McpCallContext<CallToolRequest>.do_multi(a: A, b: B): CallToolResult =
        CallToolResult(content = listOf(TextContent(text = "${a.x}, ${b.y}")))
}

internal class ZeroParamTool {
    @Schema
    suspend fun McpCallContext<CallToolRequest>.do_nothing(): CallToolResult =
        CallToolResult(content = listOf(TextContent(text = "ok")))
}

internal class DefaultParamTool {
    @Schema
    suspend fun McpCallContext<CallToolRequest>.do_default(
        prefix: String,
        limit: Int = 7,
        suffix: String,
        recursive: Boolean = true,
    ): CallToolResult =
        CallToolResult(content = listOf(TextContent(text = "$prefix:$limit:$suffix:$recursive")))
}

@JvmInline
@Serializable
internal value class ToolPath(val value: String)

internal class ValueClassTool {
    @Schema
    suspend fun McpCallContext<CallToolRequest>.do_value(path: ToolPath): CallToolResult =
        CallToolResult(content = listOf(TextContent(text = path.value)))
}

internal class NullableValueClassTool {
    @Schema
    suspend fun McpCallContext<CallToolRequest>.do_nullable_value(path: ToolPath?): CallToolResult =
        CallToolResult(content = listOf(TextContent(text = path?.value ?: "null")))
}

@JvmInline
@Serializable
internal value class ToolId(val value: Int)

internal class PrimitiveValueClassTool {
    @Schema
    suspend fun McpCallContext<CallToolRequest>.do_primitive_value(id: ToolId): CallToolResult =
        CallToolResult(content = listOf(TextContent(text = id.value.toString())))
}

internal class NullablePrimitiveValueClassTool {
    @Schema
    suspend fun McpCallContext<CallToolRequest>.do_nullable_primitive_value(id: ToolId?): CallToolResult =
        CallToolResult(content = listOf(TextContent(text = id?.value?.toString() ?: "null")))
}

@JvmInline
@Serializable
internal value class NullableInnerPath(val value: String?)

internal class NullableUnderlyingValueClassTool {
    @Schema
    suspend fun McpCallContext<CallToolRequest>.do_nullable_underlying_value(path: NullableInnerPath?): CallToolResult =
        CallToolResult(content = listOf(TextContent(text = path?.value ?: "null")))
}
