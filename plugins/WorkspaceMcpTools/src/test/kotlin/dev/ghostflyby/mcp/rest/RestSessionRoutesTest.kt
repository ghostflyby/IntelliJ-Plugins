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

    @Test
    fun `session file write routes create replace and delete relative paths`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val sessionId = client.createSessionId(projectPathFixture.get().toString())
            val created = client.post("/api/v1/session/files/session-created.txt") {
                header("X-Session-Id", sessionId)
                setBody("created through session")
            }
            Assertions.assertEquals(HttpStatusCode.Created, created.status)

            val replaced = client.put("/api/v1/session/files/session-created.txt") {
                header("X-Session-Id", sessionId)
                setBody("replaced through session")
            }
            Assertions.assertEquals(HttpStatusCode.OK, replaced.status)
            val read = client.get("/api/v1/session/files/session-created.txt") {
                header("X-Session-Id", sessionId)
            }
            Assertions.assertEquals("replaced through session", read.bodyAsText().trim())

            val deleted = client.delete("/api/v1/session/files/session-created.txt") {
                header("X-Session-Id", sessionId)
            }
            Assertions.assertEquals(HttpStatusCode.OK, deleted.status)
            val missing = client.get("/api/v1/session/files/session-created.txt") {
                header("X-Session-Id", sessionId)
            }
            Assertions.assertEquals(HttpStatusCode.NotFound, missing.status)
        }
    }

    @Test
    fun `session patch route updates relative file target`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val sessionId = client.createSessionId(projectPathFixture.get().toString())
            val patch = """*** Begin Patch
*** Update File: plain.txt
@@
-hello sample
+hello session patched
*** End Patch"""
            val response = client.patch("/api/v1/session/files/plain.txt") {
                header("X-Session-Id", sessionId)
                accept(ContentType.Application.Json)
                setBody(patch)
            }
            Assertions.assertEquals(HttpStatusCode.OK, response.status)

            val read = client.get("/api/v1/session/files/plain.txt") {
                header("X-Session-Id", sessionId)
            }
            Assertions.assertEquals("hello session patched", read.bodyAsText().trim())
        }
    }

    @Test
    fun `session search text route searches under relative path`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val sessionId = client.createSessionId(projectPathFixture.get().toString())
            val response = client.get("/api/v1/session/search/text/glob?query=RootFile&limit=10") {
                header("X-Session-Id", sessionId)
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            val hits = json.parseToJsonElement(response.bodyAsText()).jsonObject["hits"]!!.jsonArray
            Assertions.assertTrue(hits.isNotEmpty(), response.bodyAsText())
        }
    }

    @Test
    fun `session navigation route executes against relative path`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val sessionId = client.createSessionId(projectPathFixture.get().toString())
            val body = """*** Goto:
@@
- class RootFile
+ XXXXXXXXXXXXXX
"""
            val response = client.post("/api/v1/session/navigation/glob/RootFile.kt") {
                header("X-Session-Id", sessionId)
                contentType(ContentType.parse("text/x-patch"))
                accept(ContentType.Application.Json)
                setBody(body)
            }
            Assertions.assertTrue(response.status.isSuccess(), response.bodyAsText())
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
