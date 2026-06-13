/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.resources.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

@TestApplication
internal class RestSessionRoutesTest {

    private val projectPathFixture = tempPathFixture()
    private val projectFixture = projectFixture(pathFixture = projectPathFixture, openAfterCreation = true)
    private val project by projectFixture
    private val moduleFixture = projectFixture.moduleFixture(name = "session-test")
    private val blueprint = Path.of(
        requireNotNull(javaClass.getResource("/fileContentIntegration")).toURI(),
    )
    private val contentRootFixture = moduleFixture.sourceRootFixture(
        pathFixture = projectFixture.pathInProjectFixture(Path.of("")),
        blueprintResourcePath = blueprint,
    )
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun refresh() {
        val globDir = projectPathFixture.get().resolve("glob")
        globDir.resolve("nested").createDirectories()
        globDir.resolve("RootFile.kt").writeText("class RootFile")
        globDir.resolve("RootFile.txt").writeText("plain")
        globDir.resolve("nested/NestedFile.kt").writeText("class NestedFile")
        contentRootFixture.get().virtualFile.refresh(false, true)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(projectPathFixture.get())?.refresh(false, true)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    @Test
    fun `session creation binds path prefix without project key or root id input`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val response = client.createSession(projectPathFixture.get().toString())
            Assertions.assertEquals(HttpStatusCode.Created, response.status)

            val parsed = json.parseToJsonElement(response.bodyAsText()).jsonObject
            Assertions.assertNotNull(parsed["sessionId"]?.jsonPrimitive?.content)
            Assertions.assertEquals(projectPathFixture.get().toString(), parsed["pathPrefix"]?.jsonPrimitive?.content)
            Assertions.assertEquals(project.name, parsed["project"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
            Assertions.assertNotNull(parsed["project"]?.jsonObject?.get("projectKey")?.jsonPrimitive?.content)
            Assertions.assertNotNull(parsed["exposedRoot"]?.jsonObject?.get("rootId")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `session file reads resolve relative paths under path prefix`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val sessionId = client.createSessionId(projectPathFixture.get().toString())
            val response = client.get("/api/v1/session/files/plain.txt") {
                header("X-Session-Id", sessionId)
            }

            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            Assertions.assertEquals("hello sample", response.bodyAsText().trim())
        }
    }

    @Test
    fun `session glob resolves relative paths under path prefix`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val sessionId = client.createSessionId(projectPathFixture.get().toString())
            val response = client.get("/api/v1/session/glob/glob?glob=**/*.kt") {
                header("X-Session-Id", sessionId)
                accept(ContentType.Application.Json)
            }

            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            val paths = json.parseToJsonElement(response.bodyAsText()).jsonArray.map { it.jsonPrimitive.content }
            Assertions.assertEquals(listOf("RootFile.kt", "nested/NestedFile.kt"), paths)
        }
    }

    @Test
    fun `session rejects paths outside path prefix`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val sessionId = client.createSessionId(projectPathFixture.get().resolve("glob").toString())
            val inside = client.get("/api/v1/session/files/RootFile.txt") {
                header("X-Session-Id", sessionId)
            }
            Assertions.assertEquals(HttpStatusCode.OK, inside.status)

            val outside = client.get("/api/v1/session/files/%2E%2E/plain.txt") {
                header("X-Session-Id", sessionId)
            }
            Assertions.assertEquals(HttpStatusCode.Forbidden, outside.status)
        }
    }

    private suspend fun io.ktor.client.HttpClient.createSession(pathPrefix: String): HttpResponse {
        return post("/api/v1/sessions") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody("""{"pathPrefix": "$pathPrefix"}""")
        }
    }

    private suspend fun io.ktor.client.HttpClient.createSessionId(pathPrefix: String): String {
        val response = createSession(pathPrefix)
        Assertions.assertEquals(HttpStatusCode.Created, response.status)
        return json.parseToJsonElement(response.bodyAsText())
            .jsonObject["sessionId"]
            ?.jsonPrimitive
            ?.content
            ?: error("missing session id")
    }
}
