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
}

internal class ValidTool {
    @Schema
    @Serializable
    data class VaIn(val x: Int)

    @Schema
    suspend fun McpCallContext<CallToolRequest>.doThing(a: VaIn): CallToolResult =
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
    fun McpCallContext<CallToolRequest>.notSuspend(a: NtIn): CallToolResult =
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
    suspend fun noReceiver(a: NrIn): CallToolResult =
        CallToolResult(content = listOf(TextContent(text = "ok")))
}
internal class MultiParamTool {
    @Serializable data class A(val x: Int)
    @Serializable
    data class B(val y: String)
    @Schema
    suspend fun McpCallContext<CallToolRequest>.doMulti(a: A, b: B): CallToolResult =
        CallToolResult(content = listOf(TextContent(text = "${a.x}, ${b.y}")))
}

internal class ZeroParamTool {
    @Schema
    suspend fun McpCallContext<CallToolRequest>.doNothing(): CallToolResult =
        CallToolResult(content = listOf(TextContent(text = "ok")))
}
