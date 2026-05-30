/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.server

import dev.ghostflyby.mcp.server.tools.WorkspaceMcpProjectToolArguments
import dev.ghostflyby.mcp.server.tools.toolArgsJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class WorkspaceMcpRequestRunnerTest {
    @Test
    fun `serialization decodes defaults when partial args`() {
        @Serializable
        data class TestArgs(
            val url: String,
            val count: Int = 42,
            val async: Boolean = true,
        )

        val json = buildJsonObject { put("url", "file:///test.txt") }
        val decoded = toolArgsJson.decodeFromJsonElement(
            serializer<TestArgs>(), json,
        )
        assertEquals("file:///test.txt", decoded.url)
        assertEquals(42, decoded.count)
        assertEquals(true, decoded.async)
    }

    @Test
    fun `serialization decodes empty object with defaults`() {
        @Serializable
        data class TestArgs(
            val url: String = "",
            val projectKey: String? = null,
            val async: Boolean = false,
        )

        val decoded = toolArgsJson.decodeFromJsonElement(
            serializer<TestArgs>(), buildJsonObject { },
        )
        assertEquals("", decoded.url)
        Assertions.assertNull(decoded.projectKey)
        assertEquals(false, decoded.async)
    }

    @Test
    fun `serialization fails on bad type`() {
        @Serializable
        data class TestArgs(val count: Int)

        val json = buildJsonObject { put("count", JsonPrimitive("not-an-int")) }
        assertTrue(
            runCatching {
                toolArgsJson.decodeFromJsonElement(
                    serializer<TestArgs>(), json,
                )
            }.exceptionOrNull() is SerializationException,
        )
    }

    @Test
    fun `serialization fails on missing required field`() {
        @Serializable
        data class TestArgs(val url: String)
        assertTrue(
            runCatching {
                toolArgsJson.decodeFromJsonElement(
                    serializer<TestArgs>(), buildJsonObject { },
                )
            }.exceptionOrNull() is SerializationException,
        )
    }

    @Test
    fun `project tool args interface`() {
        @Serializable
        data class MyArgs(
            val url: String,
            override val projectKey: String? = null,
            override val projectPath: String? = null,
        ) : WorkspaceMcpProjectToolArguments

        val args = MyArgs(url = "/test", projectKey = "my-key", projectPath = "/tmp")
        assertEquals("my-key", args.projectKey)
        assertEquals("/tmp", args.projectPath)
    }

}
