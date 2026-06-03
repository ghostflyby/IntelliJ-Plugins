/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.*
import dev.ghostflyby.mcp.sdk.workspaceProjectKey
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.resources.Resources
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path

@TestApplication
internal class RestFileTest {

    private val projectPathFixture = tempPathFixture()
    private val projectFixture = projectFixture(pathFixture = projectPathFixture, openAfterCreation = true)
    private val project by projectFixture
    private val moduleFixture = projectFixture.moduleFixture(name = "file-content-test")
    private val blueprint = Path.of(
        requireNotNull(javaClass.getResource("/fileContentIntegration")).toURI(),
    )
    // Source root at project root level so files resolve via project.basePath + relativePath
    private val contentRootFixture = moduleFixture.sourceRootFixture(
        pathFixture = projectFixture.pathInProjectFixture(Path.of("")),
        blueprintResourcePath = blueprint,
    )

    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun refreshFixtureContent() {
        contentRootFixture.get().virtualFile.refresh(false, true)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    @Test
    fun `file content returns text body for plain file`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val response = client.get("/api/v1/projects/$key/files/plain.txt")
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            Assertions.assertEquals("hello sample", response.bodyAsText().trim())
        }
    }

    @Test
    fun `file meta returns JSON metadata`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val response = client.get("/api/v1/projects/$key/files/plain.txt?meta")
            Assertions.assertEquals(HttpStatusCode.OK, response.status)

            val body = response.bodyAsText()
            val parsed = json.parseToJsonElement(body).jsonObject
            Assertions.assertEquals("plain.txt", parsed["name"]?.jsonPrimitive?.content)
            Assertions.assertEquals("WORKSPACE_TEXT", parsed["classification"]?.jsonPrimitive?.content)
            Assertions.assertTrue(
                parsed["readableKinds"]?.jsonArray?.any { it.jsonPrimitive.content == "structure" } == true,
            )
            Assertions.assertTrue(
                parsed["writableKinds"]?.jsonArray?.any { it.jsonPrimitive.content == "patch" } == true,
            )
        }
    }

    @Test
    fun `file exists returns true for existing file`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val response = client.get("/api/v1/projects/$key/files/plain.txt?exists")
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            Assertions.assertEquals("true", response.bodyAsText())
        }
    }

    @Test
    fun `file exists returns false for missing file`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val response = client.get("/api/v1/projects/$key/files/missing.txt?exists")
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            Assertions.assertEquals("false", response.bodyAsText())
        }
    }

    @Test
    fun `missing file returns 404`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val response = client.get("/api/v1/projects/$key/files/nonexistent.txt")
            Assertions.assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `root URL reads workspace file and missing root file returns 404`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val rootId = client.firstWorkspaceRootId(key, json)
            val response = client.get("/api/v1/projects/$key/files/$rootId/plain.txt")
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            Assertions.assertEquals("hello sample", response.bodyAsText().trim())

            val missing = client.get("/api/v1/projects/$key/files/$rootId/not-found.txt")
            Assertions.assertEquals(HttpStatusCode.NotFound, missing.status)
        }
    }

    @Test
    fun `file meta and content compound response`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val response = client.get("/api/v1/projects/$key/files/plain.txt?meta&content")
            Assertions.assertEquals(HttpStatusCode.OK, response.status)

            val body = response.bodyAsText()
            val parsed = json.parseToJsonElement(body).jsonObject
            Assertions.assertTrue(parsed.containsKey("meta"))
            Assertions.assertTrue(parsed.containsKey("content"))
            Assertions.assertEquals("hello sample", parsed["content"]?.jsonPrimitive?.content?.trim())
        }
    }

    @Test
    fun `file glob lists source root files`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val response = client.get("/api/v1/projects/$key/files/src?glob=*")
            Assertions.assertEquals(HttpStatusCode.OK, response.status)

            val body = response.bodyAsText()
            val parsed = json.parseToJsonElement(body).jsonArray
            Assertions.assertTrue(parsed.isNotEmpty())
            val names = parsed.map { it.jsonPrimitive.content }
            Assertions.assertTrue(names.any { it.endsWith(".md") || it.endsWith(".xml") })
        }
    }
}
