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
import kotlin.reflect.KFunction
import kotlin.reflect.full.declaredMemberExtensionFunctions
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.valueParameters

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
    fun `methodhandle valid tool invocation`() = runBlocking {
        val result = callAcceptedToolWithMethodHandle(
            toolClass = ValidTool::class,
            functionName = "doThing",
            arguments = buildJsonObject {
                put("a", buildJsonObject { put("x", 1) })
            },
        )

        assertText(result, "ok")
    }

    @Test
    fun `methodhandle handler factory accepts only invocation plan`() {
        assertEquals(
            ToolInvocationPlan::class,
            ::buildMethodHandleHandler.valueParameters.single().type.classifier,
        )
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
    fun `methodhandle non-suspend invocation`() = runBlocking {
        val result = callAcceptedToolWithMethodHandle(
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
    fun `methodhandle regular member invocation`() = runBlocking {
        val result = callAcceptedToolWithMethodHandle(
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
    fun `methodhandle multi param invocation`() = runBlocking {
        val result = callAcceptedToolWithMethodHandle(
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
    fun `methodhandle zero param invocation`() = runBlocking {
        val result = callAcceptedToolWithMethodHandle(
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
    fun `methodhandle valid tool reports missing argument`() = runBlocking {
        val result = callAcceptedToolWithMethodHandle(
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
    fun `methodhandle multi param reports missing argument`() = runBlocking {
        val result = callAcceptedToolWithMethodHandle(
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

    @Test
    fun `methodhandle valid tool reports invalid argument`() = runBlocking {
        val result = callAcceptedToolWithMethodHandle(
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
        return callAcceptedTool(
            toolClass = toolClass,
            functionName = functionName,
            arguments = arguments,
            handlerFactory = { _, _, accepted -> accepted.handler },
        )
    }

    private suspend fun callAcceptedToolWithMethodHandle(
        toolClass: KClass<*>,
        functionName: String,
        arguments: JsonObject,
    ): CallToolResult {
        return callAcceptedTool(
            toolClass = toolClass,
            functionName = functionName,
            arguments = arguments,
            handlerFactory = { instance, func, _ ->
                buildMethodHandleHandler(compileToolInvocationPlan(instance, func))
            },
        )
    }

    private suspend fun callAcceptedTool(
        toolClass: KClass<*>,
        functionName: String,
        arguments: JsonObject,
        handlerFactory: (Any, KFunction<*>, ToolReflectionResult.Accepted) -> Handler,
    ): CallToolResult {
        val func = (toolClass.declaredMemberFunctions + toolClass.declaredMemberExtensionFunctions)
            .single { it.name == functionName }
        val instance = toolClass.java.getDeclaredConstructor().newInstance()
        val accepted = acceptedTool(toolClass, instance, func)
        val handler = handlerFactory(instance, func, accepted)
        return handler.invoke(
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
        instance: Any,
        func: KFunction<*>,
    ): ToolReflectionResult.Accepted {
        return when (val result = reflectOneTool(toolClass, instance, func)) {
            is ToolReflectionResult.Accepted -> result
            is ToolReflectionResult.Rejected -> fail("Expected ${func.name} to be accepted, rejected: ${result.reasons}")
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
