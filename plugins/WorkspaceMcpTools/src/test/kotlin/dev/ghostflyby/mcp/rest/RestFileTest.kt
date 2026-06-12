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
import io.ktor.server.resources.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
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
        globDir.resolve("RootFile.kt").writeText(
            """
            class RootFile {
                fun marker() {
                    println("ok")
                }
            }
            """.trimIndent(),
        )
        globDir.resolve("RootScript.kts").writeText("println(\"script\")")
        globDir.resolve("RootFile.txt").writeText("plain")
        globDir.resolve("nested/NestedFile.kt").writeText("class NestedFile")
        projectPathFixture.get().resolve("range.txt").writeText("one\ntwo\nthree\nfour\nfive\n")
        projectPathFixture.get().resolve("binary.bin").writeBytes(byteArrayOf(0, 1, 2, 3))
        contentRootFixture.get().virtualFile.refresh(false, true)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(secondRootPathFixture.get())?.refresh(false, true)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    @Test
    fun `file content returns text body for plain file`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
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
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val response =
                client.get(client.rootPathUrlByRootUrl(key, json, projectRootUrl(), "plain.txt", meta = true)) {
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
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val response =
                client.get(client.rootPathUrlByRootUrl(key, json, projectRootUrl(), "plain.txt", meta = true))
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            Assertions.assertEquals(TestMarkdownContentType, response.responseContentType())

            val body = response.bodyAsText()
            Assertions.assertTrue(body.startsWith("---\n"))
            Assertions.assertTrue(body.contains("name: plain.txt"))
            Assertions.assertTrue(body.contains("classification: WORKSPACE_TEXT"))
        }
    }

    @Test
    fun `file meta false returns content body`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val response =
                client.get(client.rootPathUrlByRootUrl(key, json, projectRootUrl(), "plain.txt", meta = false))
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            Assertions.assertEquals("hello sample", response.bodyAsText().trim())
        }
    }

    @Test
    fun `file exists returns true for existing file`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val response =
                client.get(client.rootPathUrlByRootUrl(key, json, projectRootUrl(), "plain.txt", exists = true))
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            Assertions.assertEquals("true", response.bodyAsText())
        }
    }

    @Test
    fun `file exists returns false for missing file`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val response =
                client.get(client.rootPathUrlByRootUrl(key, json, projectRootUrl(), "missing.txt", exists = true))
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            Assertions.assertEquals("false", response.bodyAsText())
        }
    }

    @Test
    fun `missing file returns 404`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
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
            application { installWorkspaceRestContentNegotiation() }
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
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val rootId = client.workspaceRootIdByUrl(key, json, projectRootUrl())
            val response = client.get(rootUrl(key, rootId, meta = true)) {
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
            application { installWorkspaceRestContentNegotiation() }
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
            application { installWorkspaceRestContentNegotiation() }
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
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val secondRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(secondRootPathFixture.get())
                ?: error("second root missing")
            val secondRootId = client.workspaceRootIdByUrl(key, json, secondRoot.url)
            val projectRootId = client.workspaceRootIdByUrl(key, json, projectRootUrl())
            val response = client.get(rootPathUrl(key, secondRootId, "second.txt", meta = true)) {
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
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val response =
                client.get(
                    client.rootPathUrlByRootUrl(
                        key,
                        json,
                        projectRootUrl(),
                        "plain.txt",
                        meta = true,
                        content = true,
                    ),
                ) {
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
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val response =
                client.get(
                    client.rootPathUrlByRootUrl(
                        key,
                        json,
                        projectRootUrl(),
                        "plain.txt",
                        meta = true,
                        content = true,
                    ),
                )
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            Assertions.assertEquals(TestMarkdownContentType, response.responseContentType())

            val body = response.bodyAsText()
            Assertions.assertTrue(body.startsWith("---\n"))
            Assertions.assertTrue(body.contains("name: plain.txt"))
            Assertions.assertTrue(body.contains("```txt\nhello sample\n```"))
        }
    }

    @Test
    fun `file structure includes line ranges in JSON and markdown`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val jsonResponse = client.get(
                client.rootPathUrlByRootUrl(key, json, projectRootUrl(), "glob/RootFile.kt", structure = true),
            ) {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, jsonResponse.status)
            val elements = json.parseToJsonElement(jsonResponse.bodyAsText())
                .jsonObject["elements"]?.jsonArray ?: error("missing structure elements")
            Assertions.assertTrue(elements.isNotEmpty())
            Assertions.assertTrue(elements.hasElementWithLineRange(), jsonResponse.bodyAsText())

            val markdown = client.get(
                client.rootPathUrlByRootUrl(key, json, projectRootUrl(), "glob/RootFile.kt", structure = true),
            )
            Assertions.assertEquals(HttpStatusCode.OK, markdown.status)
            Assertions.assertTrue(Regex("""\[\d+-\d+]""").containsMatchIn(markdown.bodyAsText()), markdown.bodyAsText())
        }
    }

    @Test
    fun `file range reads return raw text`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val byEnd = client.get(
                client.rootPathUrlByRootUrl(key, json, projectRootUrl(), "range.txt", startLine = 2, endLine = 4),
            )
            Assertions.assertEquals(HttpStatusCode.OK, byEnd.status)
            Assertions.assertEquals("two\nthree\nfour", byEnd.bodyAsText())

            val byMax = client.get(
                client.rootPathUrlByRootUrl(key, json, projectRootUrl(), "range.txt", startLine = 3, maxLines = 2),
            )
            Assertions.assertEquals("three\nfour", byMax.bodyAsText())

            val around = client.get(
                client.rootPathUrlByRootUrl(key, json, projectRootUrl(), "range.txt", aroundLine = 3, radius = 1),
            )
            Assertions.assertEquals("two\nthree\nfour", around.bodyAsText())
        }
    }

    @Test
    fun `file range reads trim content inside compound responses`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val response = client.get(
                client.rootPathUrlByRootUrl(
                    key,
                    json,
                    projectRootUrl(),
                    "range.txt",
                    meta = true,
                    content = true,
                    exists = true,
                    startLine = 1,
                    endLine = 2,
                ),
            ) {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            val parsed = json.parseToJsonElement(response.bodyAsText()).jsonObject
            Assertions.assertTrue(parsed.containsKey("meta"))
            Assertions.assertEquals("true", parsed["exists"]?.jsonPrimitive?.content)
            Assertions.assertEquals("one\ntwo", parsed["content"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `file range reads combine with explicit non-content flags`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val exists = client.get(
                client.rootPathUrlByRootUrl(
                    key,
                    json,
                    projectRootUrl(),
                    "range.txt",
                    exists = true,
                    startLine = 1,
                    endLine = 2,
                ),
            ) {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, exists.status)
            val existsBody = json.parseToJsonElement(exists.bodyAsText()).jsonObject
            Assertions.assertEquals("true", existsBody["exists"]?.jsonPrimitive?.content)
            Assertions.assertEquals("one\ntwo", existsBody["content"]?.jsonPrimitive?.content)

            val structure = client.get(
                client.rootPathUrlByRootUrl(
                    key,
                    json,
                    projectRootUrl(),
                    "glob/RootFile.kt",
                    meta = true,
                    structure = true,
                    startLine = 1,
                    endLine = 2,
                ),
            )
            Assertions.assertEquals(HttpStatusCode.OK, structure.status)
            Assertions.assertEquals(TestMarkdownContentType, structure.responseContentType())
            Assertions.assertTrue(structure.bodyAsText().contains("name: RootFile.kt"))
            Assertions.assertTrue(structure.bodyAsText().contains("RootFile"))
            Assertions.assertTrue(
                structure.bodyAsText().contains("```kt\nclass RootFile {\n    fun marker() {"),
                structure.bodyAsText(),
            )
            Assertions.assertFalse(structure.bodyAsText().contains("println(\"ok\")"), structure.bodyAsText())
        }
    }

    @Test
    fun `file range reads validate query and target kind`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val invalidCombination = client.get(
                client.rootPathUrlByRootUrl(
                    key,
                    json,
                    projectRootUrl(),
                    "range.txt",
                    startLine = 1,
                    endLine = 2,
                    maxLines = 1,
                ),
            )
            Assertions.assertEquals(HttpStatusCode.BadRequest, invalidCombination.status)

            val invalidValue = client.get(
                client.rootPathUrlByRootUrl(key, json, projectRootUrl(), "range.txt", startLine = 0, endLine = 1),
            )
            Assertions.assertEquals(HttpStatusCode.BadRequest, invalidValue.status)

            val directory = client.get(
                client.rootPathUrlByRootUrl(key, json, projectRootUrl(), "src", startLine = 1, endLine = 1),
            )
            Assertions.assertEquals(HttpStatusCode.BadRequest, directory.status)

            val binary = client.get(
                client.rootPathUrlByRootUrl(key, json, projectRootUrl(), "binary.bin", startLine = 1, endLine = 1),
            )
            Assertions.assertEquals(HttpStatusCode.BadRequest, binary.status)
        }
    }

    @Test
    fun `file glob star matches current directory files only`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val rootId = client.workspaceRootIdByUrl(key, json, projectRootUrl())
            val response = client.get(globPathUrl(key, rootId, "glob", glob = listOf("*.kt"))) {
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
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val rootId = client.workspaceRootIdByUrl(key, json, projectRootUrl())
            val response = client.get(globPathUrl(key, rootId, "glob", glob = listOf("**/*.kt")))
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            Assertions.assertEquals(TestMarkdownContentType, response.responseContentType())

            val names = response.bodyAsText().lines().filter { it.isNotBlank() }
            Assertions.assertEquals(listOf("@ ", "RootFile.kt", "@ nested/", "NestedFile.kt"), names)
        }
    }

    @Test
    fun `file glob merges multiple patterns and keeps JSON path array`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val rootId = client.workspaceRootIdByUrl(key, json, projectRootUrl())
            val response = client.get(
                globPathUrl(key, rootId, "glob", glob = listOf("**/*.kt", "**/*.kts")),
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
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val rootId = client.workspaceRootIdByUrl(key, json, projectRootUrl())
            val fileTarget = client.get(globPathUrl(key, rootId, "plain.txt", glob = listOf("*.kt")))
            Assertions.assertEquals(HttpStatusCode.BadRequest, fileTarget.status)

            val invalid = client.get(globPathUrl(key, rootId, "glob", glob = listOf("**foo")))
            Assertions.assertEquals(HttpStatusCode.BadRequest, invalid.status)
        }
    }

    @Test
    fun `glob prefix block single root file`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val rootId = client.workspaceRootIdByUrl(key, json, projectRootUrl())
            val response = client.get(globPathUrl(key, rootId, "glob", glob = listOf("*.txt"))) {
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
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val rootId = client.workspaceRootIdByUrl(key, json, projectRootUrl())
            val response = client.get(globPathUrl(key, rootId, "glob", glob = listOf("**/*.kt")))
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
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val rootId = client.workspaceRootIdByUrl(key, json, projectRootUrl())
            val response = client.get(globPathUrl(key, rootId, "glob", glob = listOf("**/*.*")))
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
    fun `directory content defaults to markdown listing and JSON accept returns listing`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val defaultResponse = client.get(client.rootPathUrlByRootUrl(key, json, projectRootUrl(), "src"))
            Assertions.assertEquals(HttpStatusCode.OK, defaultResponse.status)
            Assertions.assertEquals(TestMarkdownContentType, defaultResponse.responseContentType())
            Assertions.assertTrue(defaultResponse.bodyAsText().lines().any { it.isNotBlank() })

            val jsonResponse = client.get(client.rootPathUrlByRootUrl(key, json, projectRootUrl(), "src")) {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, jsonResponse.status)
            Assertions.assertTrue(json.parseToJsonElement(jsonResponse.bodyAsText()).jsonObject.containsKey("children"))
        }
    }
}

private fun JsonArray.hasElementWithLineRange(): Boolean {
    return any { element ->
        val obj = element as? JsonObject ?: return@any false
        val hasRange = obj["startLine"]?.jsonPrimitive?.intOrNull != null &&
                obj["endLine"]?.jsonPrimitive?.intOrNull != null
        hasRange || (obj["children"] as? JsonArray)?.hasElementWithLineRange() == true
    }
}
