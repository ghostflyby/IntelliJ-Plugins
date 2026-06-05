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
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.resources.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

            val response = client.get(apiUrl(Api.Projects())) {
                accept(ContentType.Application.Json)
            }
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
    fun `project list defaults to markdown table`() {
        project
        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val response = client.get(apiUrl(Api.Projects()))
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            Assertions.assertEquals(TestMarkdownContentType, response.responseContentType())
            Assertions.assertTrue(response.bodyAsText().contains("| projectKey | name | basePath |"))
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

            val response = client.get(apiUrl(Api.Project(key))) {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, response.status)

            val body = response.bodyAsText()
            val parsed = json.parseToJsonElement(body).jsonObject
            Assertions.assertEquals(key, parsed["projectKey"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `project roots lists workspace roots without exposing absolute id`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val response = client.get(apiUrl(Api.Project.Roots(Api.Project(key)))) {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, response.status)

            val root = json.parseToJsonElement(response.bodyAsText()).jsonArray.first().jsonObject
            val id = root["id"]?.jsonPrimitive?.content ?: ""
            Assertions.assertTrue(id.startsWith("workspace"))
            Assertions.assertFalse(id.contains("/"))
            Assertions.assertEquals("workspace", root["kind"]?.jsonPrimitive?.content)
            Assertions.assertEquals("true", root["readable"]?.jsonPrimitive?.content)
            Assertions.assertEquals("true", root["writable"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `project roots default to markdown table`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val response = client.get(apiUrl(Api.Project.Roots(Api.Project(key))))
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            Assertions.assertEquals(TestMarkdownContentType, response.responseContentType())
            Assertions.assertTrue(
                response.bodyAsText().contains("| id | displayName | kind | readable | writable | url |"),
            )
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
            val response = client.get(apiUrl(Api.Project("nonexistent")))
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            Assertions.assertTrue(body.contains("projectKey"))
        }
    }
}
