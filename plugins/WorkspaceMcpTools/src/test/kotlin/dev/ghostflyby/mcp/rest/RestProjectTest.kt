/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import dev.ghostflyby.mcp.sdk.workspaceProjectKey
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.resources.Resources
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

@TestApplication
internal class RestProjectTest {

    private val projectPathFixture = tempPathFixture()
    private val projectFixture = projectFixture(pathFixture = projectPathFixture, openAfterCreation = true)
    private val project by projectFixture

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `project list returns open projects`() {
        project
        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val response = client.get("/api/v1/projects")
            Assertions.assertEquals(HttpStatusCode.OK, response.status)

            val body = response.bodyAsText()
            val parsed = json.parseToJsonElement(body).jsonArray
            Assertions.assertTrue(parsed.isNotEmpty())

            val first = parsed[0].jsonObject
            Assertions.assertTrue(first.containsKey("projectKey"))
            Assertions.assertTrue(first.containsKey("name"))
            Assertions.assertTrue(first.containsKey("basePath"))
        }
    }

    @Test
    fun `project detail returns project metadata`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val response = client.get("/api/v1/projects/$key")
            Assertions.assertEquals(HttpStatusCode.OK, response.status)

            val body = response.bodyAsText()
            val parsed = json.parseToJsonElement(body).jsonObject
            Assertions.assertEquals(key, parsed["projectKey"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `unknown project key falls back to single open project`() {
        project
        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            // With only one open project, the resolver falls back to it
            val response = client.get("/api/v1/projects/nonexistent")
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            Assertions.assertTrue(body.contains("projectKey"))
        }
    }
}
