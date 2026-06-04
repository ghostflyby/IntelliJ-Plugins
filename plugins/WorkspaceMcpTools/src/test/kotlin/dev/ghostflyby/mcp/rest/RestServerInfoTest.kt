/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.resources.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class RestServerInfoTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `server info returns instance key and version`() {
        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val response = client.get("/api/v1/server/info") {
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.OK, response.status)

            val body = response.bodyAsText()
            val parsed = json.decodeFromString<Map<String, String>>(body)
            assertTrue(parsed.containsKey("instanceKey"))
            assertTrue(parsed.containsKey("version"))
        }
    }

    @Test
    fun `server info defaults to markdown front matter`() {
        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val response = client.get("/api/v1/server/info")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(TestMarkdownContentType, response.responseContentType())

            val body = response.bodyAsText()
            assertTrue(body.startsWith("---\n"))
            assertTrue(body.contains("instanceKey:"))
            assertTrue(body.contains("version:"))
        }
    }
}
