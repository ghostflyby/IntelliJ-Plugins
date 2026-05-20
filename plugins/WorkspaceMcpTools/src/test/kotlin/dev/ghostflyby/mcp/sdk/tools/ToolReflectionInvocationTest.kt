/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk.tools

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredMemberExtensionFunctions
import kotlin.reflect.full.declaredMemberFunctions

internal class ToolReflectionInvocationTest {

    @Test
    fun `valid tool invocation`() = runBlocking {
        val result = callAcceptedTool(
            toolClass = ValidTool::class,
            functionName = "doThing",
            arguments = buildJsonObject {
                put("a", buildJsonObject { put("x", 1) })
            },
        )

        assertText(result, "ok")
    }

    @Test
    fun `non-suspend invocation`() = runBlocking {
        val result = callAcceptedTool(
            toolClass = NonSuspendTool::class,
            functionName = "notSuspend",
            arguments = buildJsonObject {
                put("a", buildJsonObject { put("x", 1) })
            },
        )

        assertText(result, "ok")
    }

    @Test
    fun `regular member invocation`() = runBlocking {
        val result = callAcceptedTool(
            toolClass = NoReceiverTool::class,
            functionName = "noReceiver",
            arguments = buildJsonObject {
                put("a", buildJsonObject { put("x", 1) })
            },
        )

        assertText(result, "ok")
    }

    @Test
    fun `multi param invocation`() = runBlocking {
        val result = callAcceptedTool(
            toolClass = MultiParamTool::class,
            functionName = "doMulti",
            arguments = buildJsonObject {
                put("a", buildJsonObject { put("x", 1) })
                put("b", buildJsonObject { put("y", "z") })
            },
        )

        assertText(result, "1, z")
    }

    @Test
    fun `zero param invocation`() = runBlocking {
        val result = callAcceptedTool(
            toolClass = ZeroParamTool::class,
            functionName = "doNothing",
            arguments = buildJsonObject { },
        )

        assertText(result, "ok")
    }

    @Test
    fun `valid tool reports missing argument`() = runBlocking {
        val result = callAcceptedTool(
            toolClass = ValidTool::class,
            functionName = "doThing",
            arguments = buildJsonObject { },
        )

        assertErrorContains(result, "a")
    }

    @Test
    fun `multi param reports missing argument`() = runBlocking {
        val result = callAcceptedTool(
            toolClass = MultiParamTool::class,
            functionName = "doMulti",
            arguments = buildJsonObject {
                put("a", buildJsonObject { put("x", 1) })
            },
        )

        assertErrorContains(result, "b")
    }

    @Test
    fun `valid tool reports invalid argument`() = runBlocking {
        val result = callAcceptedTool(
            toolClass = ValidTool::class,
            functionName = "doThing",
            arguments = buildJsonObject {
                put("a", buildJsonObject { put("x", "bad") })
            },
        )

        assertErrorContains(result, "Invalid arguments")
    }

    private suspend fun callAcceptedTool(
        toolClass: KClass<*>,
        functionName: String,
        arguments: JsonObject,
    ): CallToolResult {
        val accepted = acceptedTool(toolClass, functionName)
        return accepted.handler.invoke(
            fakeClientConnection(),
            CallToolRequest(
                CallToolRequestParams(
                    name = accepted.tool.name,
                    arguments = arguments,
                ),
            ),
        )
    }

    private fun acceptedTool(
        toolClass: KClass<*>,
        functionName: String,
    ): ToolReflectionResult.Accepted {
        val func = (toolClass.declaredMemberFunctions + toolClass.declaredMemberExtensionFunctions)
            .single { it.name == functionName }
        val instance = toolClass.java.getDeclaredConstructor().newInstance()
        return when (val result = reflectOneTool(toolClass, instance, func)) {
            is ToolReflectionResult.Accepted -> result
            is ToolReflectionResult.Rejected -> fail("Expected $functionName to be accepted, rejected: ${result.reasons}")
        } as ToolReflectionResult.Accepted
    }

    private fun fakeClientConnection(): ClientConnection {
        return Proxy.newProxyInstance(
            ClientConnection::class.java.classLoader,
            arrayOf(ClientConnection::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getSessionId" -> "test-session"
                "toString" -> "FakeClientConnection(test-session)"
                "hashCode" -> 1
                "equals" -> args?.firstOrNull() === this
                else -> error("Unexpected ClientConnection call in tool invocation test: ${method.name}")
            }
        } as ClientConnection
    }

    private fun assertText(result: CallToolResult, expected: String) {
        assertEquals(null, result.isError)
        assertEquals(expected, (result.content.single() as TextContent).text)
    }

    private fun assertErrorContains(result: CallToolResult, text: String) {
        assertEquals(true, result.isError)
        assertTrue((result.content.single() as TextContent).text.contains(text))
    }
}
