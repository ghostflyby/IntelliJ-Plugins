/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk.tools

import com.intellij.testFramework.junit5.TestApplication
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.reflect.full.declaredMemberExtensionFunctions
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.valueParameters
import kotlin.time.Duration.Companion.nanoseconds

@TestApplication
internal class ToolPerformanceTest {

    companion object {
        private const val WARMUP = 2_000
        private const val MEASURED = 20_000

        private val reflectHandler: Handler by lazy {
            val tc = PerfTool::class
            val fn = (tc.declaredMemberFunctions + tc.declaredMemberExtensionFunctions)
                .single { it.name == "do_perf" }
            buildKotlinReflectHandler(PerfTool(), fn, ToolMethodInfo(fn.name, fn.valueParameters.map { it.type }))
        }

        private val mhHandler: Handler by lazy {
            val tc = PerfTool::class
            val fn = (tc.declaredMemberFunctions + tc.declaredMemberExtensionFunctions)
                .single { it.name == "do_perf" }
            buildMethodHandleHandler(compileToolInvocationPlan(PerfTool(), fn))
        }

        private val testRequest: CallToolRequest by lazy {
            CallToolRequest(
                CallToolRequestParams(
                    name = "do_perf",
                    arguments = buildJsonObject {
                        put("url", "file:///tmp/test/file.txt")
                        put("offset", 42)
                        put("limit", 100)
                        put("recursive", true)
                        put("tags", buildJsonArray { add("t1"); add("t2"); add("t3") })
                    },
                ),
            )
        }

        private val testClient: ClientConnection by lazy {
            Proxy.newProxyInstance(
                ClientConnection::class.java.classLoader,
                arrayOf(ClientConnection::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getSessionId" -> "perf-test"
                    "toString" -> "FakeClientConnection(perf-test)"
                    "hashCode" -> 1
                    "equals" -> args?.firstOrNull() === this
                    else -> error("unexpected: ${method.name}")
                }
            } as ClientConnection
        }
    }

    @Test
    fun `reflect handler correctness`(): Unit = runBlocking {
        val r = reflectHandler.invoke(testClient, testRequest)
        verify(r)
    }

    @Test
    fun `methodHandle handler correctness`(): Unit = runBlocking {
        val r = mhHandler.invoke(testClient, testRequest)
        verify(r)
    }

    @Test
    fun `reflect handler performance`(): Unit = runBlocking {
        repeat(WARMUP) { reflectHandler.invoke(testClient, testRequest) }
        val start = Clock.System.now()
        repeat(MEASURED) { reflectHandler.invoke(testClient, testRequest) }
        val elapsed = Clock.System.now() - start
        val avg = elapsed / MEASURED
        println("Reflect: $MEASURED calls in ${elapsed}, avg ${avg}/call")
        assertTrue(avg < 500_000.nanoseconds, "too slow: $avg > 500_000ns")
    }

    @Test
    fun `methodHandle handler performance`(): Unit = runBlocking {
        repeat(WARMUP) { mhHandler.invoke(testClient, testRequest) }
        val start = Clock.System.now()
        repeat(MEASURED) { mhHandler.invoke(testClient, testRequest) }
        val elapsed = Clock.System.now() - start
        val avg = elapsed / MEASURED
        println("MethodHandle: $MEASURED calls in ${elapsed}, avg ${avg}/call")
        assertTrue(avg < 500_000.nanoseconds, "too slow: $avg > 500_000ns")
    }

    private fun verify(r: CallToolResult) {
        assertNull(r.isError)
        assertNotNull(r.structuredContent)
        assertEquals("file:///tmp/test/file.txt", r.structuredContent!!["url"]?.jsonPrimitive?.content)
    }
}
