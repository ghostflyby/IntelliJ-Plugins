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
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
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

        restTestApplication {
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response = sessionClient.get(rootPathUrlByRootUrl("plain.txt"))
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            Assertions.assertEquals("hello sample", response.bodyAsText().trim())
        }
    }

    @Test
    fun `file meta returns JSON metadata`() {
        project

        restTestApplication {
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response =
                sessionClient.get(rootPathUrlByRootUrl("plain.txt", meta = true)) {
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

        restTestApplication {
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response =
                sessionClient.get(rootPathUrlByRootUrl("plain.txt", meta = true))
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

        restTestApplication {
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response =
                sessionClient.get(rootPathUrlByRootUrl("plain.txt", meta = false))
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            Assertions.assertEquals("hello sample", response.bodyAsText().trim())
        }
    }

    @Test
    fun `file exists returns true for existing file`() {
        project

        restTestApplication {
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response =
                sessionClient.get(rootPathUrlByRootUrl("plain.txt", exists = true))
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            Assertions.assertEquals("true", response.bodyAsText())
        }
    }

    @Test
    fun `file exists returns false for missing file`() {
        project

        restTestApplication {
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response =
                sessionClient.get(rootPathUrlByRootUrl("missing.txt", exists = true))
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            Assertions.assertEquals("false", response.bodyAsText())
        }
    }

    @Test
    fun `missing file returns 404`() {
        project

        restTestApplication {
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response = sessionClient.get(rootPathUrlByRootUrl("nonexistent.txt"))
            Assertions.assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `root URL reads workspace file and missing root file returns 404`() {
        project

        restTestApplication {
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response = sessionClient.get(rootPathUrlByRootUrl("plain.txt"))
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            Assertions.assertEquals("hello sample", response.bodyAsText().trim())

            val missing = sessionClient.get(rootPathUrlByRootUrl("not-found.txt"))
            Assertions.assertEquals(HttpStatusCode.NotFound, missing.status)
        }
    }

    @Test
    fun `root URL without relative path reads root itself`() {
        val url = rootUrl("project-key", "workspace-0")
        Assertions.assertEquals("/api/v1/projects/project-key/roots/workspace-0", url)
    }

    @Test
    fun `missing session header returns 404`() {
        project

        restTestApplication {

            val response = client.get("/api/v1/files/plain.txt")
            Assertions.assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `file problems reports syntax errors as markdown`() {
        project
        val broken = projectPathFixture.get().resolve("broken.xml")
        broken.writeText("<root>")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(broken)?.refresh(false, false)

        restTestApplication {
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response =
                sessionClient.get("${rootPathUrl("broken.xml")}?problems=true&minSeverity=ERROR")
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            Assertions.assertEquals(TestMarkdownContentType, response.responseContentType())
            val body = response.bodyAsText()
            Assertions.assertTrue(body.contains("## Problems"), body)
            Assertions.assertTrue(body.contains("SyntaxError"), body)
            Assertions.assertFalse(body.contains("## Diagnostics"), body)
        }
    }

    @Test
    fun `inspection route accepts inspect file operations`() {
        project
        val broken = projectPathFixture.get().resolve("inspection-broken.xml")
        broken.writeText("<root>")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(broken)?.refresh(false, false)

        restTestApplication {
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val body = """*** Begin Patch
*** Inspect File: inspection-broken.xml
*** End Patch"""
            val response = sessionClient.post("/api/v1/inspections/.?minSeverity=ERROR") {
                setBody(body)
            }
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            val text = response.bodyAsText()
            Assertions.assertTrue(text.contains("SyntaxError"), text)
            Assertions.assertTrue(text.contains("inspection-broken.xml"), text)
        }
    }

    @Test
    fun `second workspace root file is classified by its containing root`() {
        project

        restTestApplication {

            val secondRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(secondRootPathFixture.get())
            assertNotNull(secondRoot)
            val secondRootClient = client.withRestSession(secondRootPathFixture.get().toString(), json)
            val response = secondRootClient.get(rootPathUrl("second.txt", meta = true)) {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, response.status)

            val parsed = json.parseToJsonElement(response.bodyAsText()).jsonObject
            Assertions.assertEquals("WORKSPACE_TEXT", parsed["classification"]?.jsonPrimitive?.content)

            val projectRootClient = client.withRestSession(projectPathFixture.get().toString(), json)
            val wrongRoot = projectRootClient.get(rootPathUrl("second.txt"))
            Assertions.assertEquals(HttpStatusCode.NotFound, wrongRoot.status)
        }
    }

    @Test
    fun `file meta and content compound response`() {
        project

        restTestApplication {
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response =
                sessionClient.get(
                    rootPathUrlByRootUrl(
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

        restTestApplication {
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response =
                sessionClient.get(
                    rootPathUrlByRootUrl(
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

        restTestApplication {
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val jsonResponse = sessionClient.get(
                rootPathUrlByRootUrl("glob/RootFile.kt", structure = true),
            ) {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, jsonResponse.status)
            val elements = json.parseToJsonElement(jsonResponse.bodyAsText())
                .jsonObject["elements"]?.jsonArray ?: error("missing structure elements")
            Assertions.assertTrue(elements.isNotEmpty())
            Assertions.assertTrue(elements.hasElementWithLineRange(), jsonResponse.bodyAsText())

            val markdown = sessionClient.get(
                rootPathUrlByRootUrl("glob/RootFile.kt", structure = true),
            )
            Assertions.assertEquals(HttpStatusCode.OK, markdown.status)
            Assertions.assertTrue(Regex("""\[\d+-\d+]""").containsMatchIn(markdown.bodyAsText()), markdown.bodyAsText())
        }
    }

    @Test
    fun `file range reads return raw text`() {
        project

        restTestApplication {
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val byEnd = sessionClient.get(
                rootPathUrlByRootUrl("range.txt", startLine = 2, endLine = 4),
            )
            Assertions.assertEquals(HttpStatusCode.OK, byEnd.status)
            Assertions.assertEquals("two\nthree\nfour", byEnd.bodyAsText())

            val byMax = sessionClient.get(
                rootPathUrlByRootUrl("range.txt", startLine = 3, maxLines = 2),
            )
            Assertions.assertEquals("three\nfour", byMax.bodyAsText())

            val around = sessionClient.get(
                rootPathUrlByRootUrl("range.txt", aroundLine = 3, radius = 1),
            )
            Assertions.assertEquals("two\nthree\nfour", around.bodyAsText())
        }
    }

    @Test
    fun `file range reads trim content inside compound responses`() {
        project

        restTestApplication {
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response = sessionClient.get(
                rootPathUrlByRootUrl(
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

        restTestApplication {
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val exists = sessionClient.get(
                rootPathUrlByRootUrl(
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

            val structure = sessionClient.get(
                rootPathUrlByRootUrl(
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

        restTestApplication {
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val invalidCombination = sessionClient.get(
                rootPathUrlByRootUrl(
                    "range.txt",
                    startLine = 1,
                    endLine = 2,
                    maxLines = 1,
                ),
            )
            Assertions.assertEquals(HttpStatusCode.BadRequest, invalidCombination.status)

            val invalidValue = sessionClient.get(
                rootPathUrlByRootUrl("range.txt", startLine = 0, endLine = 1),
            )
            Assertions.assertEquals(HttpStatusCode.BadRequest, invalidValue.status)

            val directory = sessionClient.get(
                rootPathUrlByRootUrl("src", startLine = 1, endLine = 1),
            )
            Assertions.assertEquals(HttpStatusCode.BadRequest, directory.status)

            val binary = sessionClient.get(
                rootPathUrlByRootUrl("binary.bin", startLine = 1, endLine = 1),
            )
            Assertions.assertEquals(HttpStatusCode.BadRequest, binary.status)
        }
    }

    @Test
    fun `file glob star matches current directory files only`() {
        project

        restTestApplication {
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response = sessionClient.get(globPathUrl("glob", glob = listOf("*.kt"))) {
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

        restTestApplication {
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response = sessionClient.get(globPathUrl("glob", glob = listOf("**/*.kt")))
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            Assertions.assertEquals(TestMarkdownContentType, response.responseContentType())

            val names = response.bodyAsText().lines().filter { it.isNotBlank() }
            Assertions.assertEquals(listOf("@ ", "RootFile.kt", "@ nested/", "NestedFile.kt"), names)
        }
    }

    @Test
    fun `file glob merges multiple patterns and keeps JSON path array`() {
        project

        restTestApplication {
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response = sessionClient.get(
                globPathUrl("glob", glob = listOf("**/*.kt", "**/*.kts")),
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

        restTestApplication {
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val fileTarget = sessionClient.get(globPathUrl("plain.txt", glob = listOf("*.kt")))
            Assertions.assertEquals(HttpStatusCode.BadRequest, fileTarget.status)

            val invalid = sessionClient.get(globPathUrl("glob", glob = listOf("**foo")))
            Assertions.assertEquals(HttpStatusCode.BadRequest, invalid.status)
        }
    }

    @Test
    fun `glob prefix block single root file`() {
        project

        restTestApplication {
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response = sessionClient.get(globPathUrl("glob", glob = listOf("*.txt"))) {
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

        restTestApplication {
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response = sessionClient.get(globPathUrl("glob", glob = listOf("**/*.kt")))
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

        restTestApplication {
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response = sessionClient.get(globPathUrl("glob", glob = listOf("**/*.*")))
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

        restTestApplication {
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val defaultResponse = sessionClient.get(rootPathUrlByRootUrl("src"))
            Assertions.assertEquals(HttpStatusCode.OK, defaultResponse.status)
            Assertions.assertEquals(TestMarkdownContentType, defaultResponse.responseContentType())
            Assertions.assertTrue(defaultResponse.bodyAsText().lines().any { it.isNotBlank() })

            val jsonResponse = sessionClient.get(rootPathUrlByRootUrl("src")) {
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
