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
import dev.ghostflyby.mcp.sdk.workspaceProjectKey
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
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
internal class RestFileTest {

    private val projectPathFixture = tempPathFixture()
    private val projectFixture = projectFixture(pathFixture = projectPathFixture, openAfterCreation = true)
    private val project by projectFixture
    private val moduleFixture = projectFixture.moduleFixture(name = "file-content-test")
    private val secondRootPathFixture = tempPathFixture()
    private val secondModuleFixture = projectFixture.moduleFixture(secondRootPathFixture, addPathToSourceRoot = true)
    private val blueprint = Path.of(
        requireNotNull(javaClass.getResource("/fileContentIntegration")).toURI(),
    )
    // Source root at project root level so files resolve via project.basePath + relativePath
    private val contentRootFixture = moduleFixture.sourceRootFixture(
        pathFixture = projectFixture.pathInProjectFixture(Path.of("")),
        blueprintResourcePath = blueprint,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private fun projectRootUrl(): String =
        requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(projectPathFixture.get())).url

    @BeforeEach
    fun refreshFixtureContent() {
        secondModuleFixture.get()
        secondRootPathFixture.get().createDirectories()
        secondRootPathFixture.get().resolve("second.txt").writeText("second root")
        val globDir = projectPathFixture.get().resolve("glob")
        globDir.resolve("nested").createDirectories()
        globDir.resolve("RootFile.kt").writeText("class RootFile")
        globDir.resolve("RootScript.kts").writeText("println(\"script\")")
        globDir.resolve("RootFile.txt").writeText("plain")
        globDir.resolve("nested/NestedFile.kt").writeText("class NestedFile")
        contentRootFixture.get().virtualFile.refresh(false, true)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(secondRootPathFixture.get())?.refresh(false, true)
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

            val response = client.get(client.rootPathUrlByRootUrl(key, json, projectRootUrl(), "plain.txt"))
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

            val response = client.get("${client.rootPathUrlByRootUrl(key, json, projectRootUrl(), "plain.txt")}?meta") {
                accept(ContentType.Application.Json)
            }
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
    fun `file meta defaults to markdown front matter`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val response = client.get("${client.rootPathUrlByRootUrl(key, json, projectRootUrl(), "plain.txt")}?meta")
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            Assertions.assertEquals(TestMarkdownContentType, response.responseContentType())

            val body = response.bodyAsText()
            Assertions.assertTrue(body.startsWith("---\n"))
            Assertions.assertTrue(body.contains("name: plain.txt"))
            Assertions.assertTrue(body.contains("classification: WORKSPACE_TEXT"))
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

            val response = client.get("${client.rootPathUrlByRootUrl(key, json, projectRootUrl(), "plain.txt")}?exists")
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

            val response =
                client.get("${client.rootPathUrlByRootUrl(key, json, projectRootUrl(), "missing.txt")}?exists")
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

            val response = client.get(client.rootPathUrlByRootUrl(key, json, projectRootUrl(), "nonexistent.txt"))
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

            val response = client.get(client.rootPathUrlByRootUrl(key, json, projectRootUrl(), "plain.txt"))
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            Assertions.assertEquals("hello sample", response.bodyAsText().trim())

            val missing = client.get(client.rootPathUrlByRootUrl(key, json, projectRootUrl(), "not-found.txt"))
            Assertions.assertEquals(HttpStatusCode.NotFound, missing.status)
        }
    }

    @Test
    fun `root URL without relative path reads root itself`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val rootId = client.workspaceRootIdByUrl(key, json, projectRootUrl())
            val response = client.get("${rootUrl(key, rootId)}?meta") {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, response.status)

            val parsed = json.parseToJsonElement(response.bodyAsText()).jsonObject
            Assertions.assertEquals("WORKSPACE_TEXT", parsed["classification"]?.jsonPrimitive?.content)
            Assertions.assertEquals("true", parsed["isDirectory"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `old file URL without root id no longer resolves project file`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val response = client.get("/api/v1/projects/$key/files/plain.txt")
            Assertions.assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `unknown root id returns 404`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val response = client.get("/api/v1/projects/$key/files/not-a-root/plain.txt")
            Assertions.assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `second workspace root file is classified by its containing root`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val secondRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(secondRootPathFixture.get())
                ?: error("second root missing")
            val secondRootId = client.workspaceRootIdByUrl(key, json, secondRoot.url)
            val projectRootId = client.workspaceRootIdByUrl(key, json, projectRootUrl())
            val response = client.get("${rootPathUrl(key, secondRootId, "second.txt")}?meta") {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, response.status)

            val parsed = json.parseToJsonElement(response.bodyAsText()).jsonObject
            Assertions.assertEquals("WORKSPACE_TEXT", parsed["classification"]?.jsonPrimitive?.content)

            val wrongRoot = client.get(rootPathUrl(key, projectRootId, "second.txt"))
            Assertions.assertEquals(HttpStatusCode.NotFound, wrongRoot.status)
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

            val response =
                client.get("${client.rootPathUrlByRootUrl(key, json, projectRootUrl(), "plain.txt")}?meta&content") {
                    accept(ContentType.Application.Json)
                }
            Assertions.assertEquals(HttpStatusCode.OK, response.status)

            val body = response.bodyAsText()
            val parsed = json.parseToJsonElement(body).jsonObject
            Assertions.assertTrue(parsed.containsKey("meta"))
            Assertions.assertTrue(parsed.containsKey("content"))
            Assertions.assertEquals("hello sample", parsed["content"]?.jsonPrimitive?.content?.trim())
        }
    }

    @Test
    fun `file meta and content default to markdown document`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val response =
                client.get("${client.rootPathUrlByRootUrl(key, json, projectRootUrl(), "plain.txt")}?meta&content")
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            Assertions.assertEquals(TestMarkdownContentType, response.responseContentType())

            val body = response.bodyAsText()
            Assertions.assertTrue(body.startsWith("---\n"))
            Assertions.assertTrue(body.contains("name: plain.txt"))
            Assertions.assertTrue(body.contains("```txt\nhello sample\n```"))
        }
    }

    @Test
    fun `file glob star matches current directory files only`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val rootId = client.workspaceRootIdByUrl(key, json, projectRootUrl())
            val response = client.get("${globPathUrl(key, rootId, "glob")}?glob=*.kt") {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, response.status)

            val body = response.bodyAsText()
            val parsed = json.parseToJsonElement(body).jsonArray
            val names = parsed.map { it.jsonPrimitive.content }
            Assertions.assertEquals(listOf("RootFile.kt"), names)
        }
    }

    @Test
    fun `file glob globstar matches current and nested directory files`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val rootId = client.workspaceRootIdByUrl(key, json, projectRootUrl())
            val response = client.get("${globPathUrl(key, rootId, "glob")}?glob=**/*.kt")
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            Assertions.assertEquals(ContentType.Text.Plain.withCharset(Charsets.UTF_8), response.responseContentType())

            val names = response.bodyAsText().lines().filter { it.isNotBlank() }
            Assertions.assertEquals(listOf("@ ", "RootFile.kt", "@ nested/", "NestedFile.kt"), names)
        }
    }

    @Test
    fun `file glob merges multiple patterns and keeps JSON path array`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val rootId = client.workspaceRootIdByUrl(key, json, projectRootUrl())
            val response = client.get(
                "${globPathUrl(key, rootId, "glob")}?glob=**/*.kt&glob=**/*.kts",
            ) {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, response.status)

            val parsed = json.parseToJsonElement(response.bodyAsText()).jsonArray
            val names = parsed.map { it.jsonPrimitive.content }
            Assertions.assertEquals(listOf("RootFile.kt", "RootScript.kts", "nested/NestedFile.kt"), names)
        }
    }

    @Test
    fun `file glob rejects file targets and invalid patterns`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val rootId = client.workspaceRootIdByUrl(key, json, projectRootUrl())
            val fileTarget = client.get("${globPathUrl(key, rootId, "plain.txt")}?glob=*.kt")
            Assertions.assertEquals(HttpStatusCode.BadRequest, fileTarget.status)

            val invalid = client.get("${globPathUrl(key, rootId, "glob")}?glob=**foo")
            Assertions.assertEquals(HttpStatusCode.BadRequest, invalid.status)
        }
    }

    @Test
    fun `glob prefix block single root file`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val rootId = client.workspaceRootIdByUrl(key, json, projectRootUrl())
            val response = client.get("${globPathUrl(key, rootId, "glob")}?glob=*.txt") {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            // JSON is unchanged; this test validates fixture
            val body = json.parseToJsonElement(response.bodyAsText()).jsonArray
            Assertions.assertEquals(listOf("RootFile.txt"), body.map { it.jsonPrimitive.content })
        }
    }

    @Test
    fun `glob prefix block mixed root and nested`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val rootId = client.workspaceRootIdByUrl(key, json, projectRootUrl())
            val response = client.get("${globPathUrl(key, rootId, "glob")}?glob=**/*.kt")
            Assertions.assertEquals(HttpStatusCode.OK, response.status)

            val lines = response.bodyAsText().lines().filter { it.isNotBlank() }
            // RootFile.kt is at root (prefix @ ), NestedFile.kt in nested/
            Assertions.assertEquals(4, lines.size)
            Assertions.assertEquals("@ ", lines[0])
            Assertions.assertEquals("RootFile.kt", lines[1])
            Assertions.assertEquals("@ nested/", lines[2])
            Assertions.assertEquals("NestedFile.kt", lines[3])
        }
    }

    @Test
    fun `glob prefix block multiple files same directory`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val rootId = client.workspaceRootIdByUrl(key, json, projectRootUrl())
            val response = client.get("${globPathUrl(key, rootId, "glob")}?glob=**/*.*")
            Assertions.assertEquals(HttpStatusCode.OK, response.status)

            val lines = response.bodyAsText().lines().filter { it.isNotBlank() }
            // All .txt/.kt/.kts files in glob/
            val prefixLines = lines.filter { it.startsWith("@") }
            // Should have @  and @ nested/
            Assertions.assertTrue(prefixLines.contains("@ "))
            Assertions.assertTrue(prefixLines.contains("@ nested/"))
        }
    }

    @Test
    fun `directory content defaults to tab indented text and JSON accept returns listing`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val defaultResponse = client.get(client.rootPathUrlByRootUrl(key, json, projectRootUrl(), "src"))
            Assertions.assertEquals(HttpStatusCode.OK, defaultResponse.status)
            Assertions.assertEquals(
                ContentType.Text.Plain.withCharset(Charsets.UTF_8),
                defaultResponse.responseContentType(),
            )
            Assertions.assertTrue(defaultResponse.bodyAsText().lines().any { it.isNotBlank() })

            val jsonResponse = client.get(client.rootPathUrlByRootUrl(key, json, projectRootUrl(), "src")) {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, jsonResponse.status)
            Assertions.assertTrue(json.parseToJsonElement(jsonResponse.bodyAsText()).jsonObject.containsKey("children"))
        }
    }
}
