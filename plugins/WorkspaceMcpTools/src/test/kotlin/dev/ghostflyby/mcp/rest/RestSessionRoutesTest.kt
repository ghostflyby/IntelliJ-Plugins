/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.roots.ModuleRootManager
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
import java.net.URLEncoder
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

@TestApplication
internal class RestSessionRoutesTest {

    private val projectPathFixture = tempPathFixture()
    private val externalPathFixture = tempPathFixture()
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
        val nestedRootPath = projectPathFixture.get().resolve("nested-session-root")
        nestedRootPath.createDirectories()
        nestedRootPath.resolve("NestedRootFile.kt").writeText("class NestedRootFile")
        val globDir = projectPathFixture.get().resolve("glob")
        globDir.resolve("nested").createDirectories()
        globDir.resolve("RootFile.kt").writeText("class RootFile")
        globDir.resolve("RootFile.txt").writeText("plain")
        globDir.resolve("nested/NestedFile.kt").writeText("class NestedFile")
        externalPathFixture.get().resolve("external.txt").writeText("external")
        externalPathFixture.get().resolve("External.kt")
            .writeText("""class External { fun ping() = "external needle" }""")
        externalPathFixture.get().resolve("nested").createDirectories()
        externalPathFixture.get().resolve("nested/ExternalSearch.kt")
            .writeText("""class ExternalSearch { val marker = "external needle" }""")
        val nestedRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(nestedRootPath)
            ?: error("nested root missing")
        ApplicationManager.getApplication().runWriteAction {
            val model = ModuleRootManager.getInstance(moduleFixture.get()).modifiableModel
            var committed = false
            try {
                model.addContentEntry(nestedRoot).addSourceFolder(nestedRoot, false)
                model.commit()
                committed = true
            } finally {
                if (!committed) model.dispose()
            }
        }
        contentRootFixture.get().virtualFile.refresh(false, true)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(projectPathFixture.get())?.refresh(false, true)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(externalPathFixture.get())?.refresh(false, true)
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
            Assertions.assertFalse(parsed["project"]?.jsonObject?.containsKey("projectKey") == true)
            Assertions.assertFalse(parsed["exposedRoot"]?.jsonObject?.containsKey("rootId") == true)
        }
    }

    @Test
    fun `session creation chooses nested root without same project ambiguity`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val nestedRoot = projectPathFixture.get().resolve("nested-session-root").toString()
            val response = client.createSession(nestedRoot)
            Assertions.assertEquals(HttpStatusCode.Created, response.status)

            val parsed = json.parseToJsonElement(response.bodyAsText()).jsonObject
            Assertions.assertEquals(nestedRoot, parsed["pathPrefix"]?.jsonPrimitive?.content)
            Assertions.assertEquals(nestedRoot, parsed["exposedRoot"]?.jsonObject?.get("path")?.jsonPrimitive?.content)
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
            val response = client.get("/api/v1/files/plain.txt") {
                header(RestSessionHeader, sessionId)
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
            val response = client.get("/api/v1/glob/glob?glob=**/*.kt") {
                header(RestSessionHeader, sessionId)
                accept(ContentType.Application.Json)
            }

            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            val paths = json.parseToJsonElement(response.bodyAsText()).jsonArray.map { it.jsonPrimitive.content }
            Assertions.assertEquals(listOf("RootFile.kt", "nested/NestedFile.kt"), paths)
        }
    }

    @Test
    fun `session glob reads full vfs url outside session path prefix`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val sessionId = client.createSessionId(projectPathFixture.get().resolve("glob").toString())
            val globUrl = encodedVfsUrl(projectPathFixture.get().resolve("glob"))
            val response = client.get("/api/v1/glob/$globUrl?glob=**/*.kt") {
                header(RestSessionHeader, sessionId)
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
            val inside = client.get("/api/v1/files/RootFile.txt") {
                header(RestSessionHeader, sessionId)
            }
            Assertions.assertEquals(HttpStatusCode.OK, inside.status)

            val outside = client.get("/api/v1/files/%2E%2E/plain.txt") {
                header(RestSessionHeader, sessionId)
            }
            Assertions.assertEquals(HttpStatusCode.Forbidden, outside.status)
        }
    }

    @Test
    fun `file route reads full vfs url outside session path prefix`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val sessionId = client.createSessionId(projectPathFixture.get().resolve("glob").toString())
            val plainTextUrl = encodedVfsUrl(projectPathFixture.get().resolve("plain.txt"))
            val response = client.get("/api/v1/files/$plainTextUrl") {
                header(RestSessionHeader, sessionId)
            }

            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            Assertions.assertEquals("hello sample", response.bodyAsText().trim())
        }
    }

    @Test
    fun `file route reads structure for full vfs url outside project`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val sessionId = client.createSessionId(projectPathFixture.get().resolve("glob").toString())
            val externalUrl = encodedVfsUrl(externalPathFixture.get().resolve("External.kt"))
            val response = client.get("/api/v1/files/$externalUrl?structure=true") {
                header(RestSessionHeader, sessionId)
                accept(ContentType.Application.Json)
            }

            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            Assertions.assertTrue(response.bodyAsText().contains("External"), response.bodyAsText())
        }
    }

    @Test
    fun `file route permits vfs url writes only for files in the session project`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val sessionId = client.createSessionId(projectPathFixture.get().resolve("glob").toString())
            val plainTextUrl = encodedVfsUrl(projectPathFixture.get().resolve("plain.txt"))
            val replaced = client.put("/api/v1/files/$plainTextUrl") {
                header(RestSessionHeader, sessionId)
                setBody("changed through project vfs url")
            }
            Assertions.assertEquals(HttpStatusCode.OK, replaced.status)

            val read = client.get("/api/v1/files/$plainTextUrl") {
                header(RestSessionHeader, sessionId)
            }
            Assertions.assertEquals("changed through project vfs url", read.bodyAsText().trim())

            val externalUrl = encodedVfsUrl(externalPathFixture.get().resolve("external.txt"))
            val externalRead = client.get("/api/v1/files/$externalUrl") {
                header(RestSessionHeader, sessionId)
            }
            Assertions.assertEquals(HttpStatusCode.OK, externalRead.status)
            Assertions.assertEquals("external", externalRead.bodyAsText().trim())

            val externalWrite = client.put("/api/v1/files/$externalUrl") {
                header(RestSessionHeader, sessionId)
                setBody("must not write")
            }
            Assertions.assertEquals(HttpStatusCode.Forbidden, externalWrite.status)
        }
    }

    @Test
    fun `file route requires valid vendor session header`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val missing = client.get("/api/v1/files/plain.txt")
            Assertions.assertEquals(HttpStatusCode.NotFound, missing.status)

            val invalid = client.get("/api/v1/files/plain.txt") {
                header(RestSessionHeader, "s_missing")
            }
            Assertions.assertEquals(HttpStatusCode.NotFound, invalid.status)
        }
    }

    @Test
    fun `deleted session no longer authorizes file routes`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val sessionId = client.createSessionId(projectPathFixture.get().toString())
            val deleted = client.delete("/api/v1/sessions/$sessionId")
            Assertions.assertEquals(HttpStatusCode.OK, deleted.status)

            val response = client.get("/api/v1/files/plain.txt") {
                header(RestSessionHeader, sessionId)
            }
            Assertions.assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `legacy session and raw vfs file routes are not registered`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val sessionId = client.createSessionId(projectPathFixture.get().toString())
            val sessionPrefix = client.get("/api/v1/session/files/plain.txt") {
                header(RestSessionHeader, sessionId)
            }
            Assertions.assertEquals(HttpStatusCode.NotFound, sessionPrefix.status)

            val rawVfs = client.get("/api/v1/vfs/file:///tmp/plain.txt") {
                header(RestSessionHeader, sessionId)
            }
            Assertions.assertEquals(HttpStatusCode.NotFound, rawVfs.status)
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
            val created = client.post("/api/v1/files/session-created.txt") {
                header(RestSessionHeader, sessionId)
                setBody("created through session")
            }
            Assertions.assertEquals(HttpStatusCode.Created, created.status)

            val replaced = client.put("/api/v1/files/session-created.txt") {
                header(RestSessionHeader, sessionId)
                setBody("replaced through session")
            }
            Assertions.assertEquals(HttpStatusCode.OK, replaced.status)
            val read = client.get("/api/v1/files/session-created.txt") {
                header(RestSessionHeader, sessionId)
            }
            Assertions.assertEquals("replaced through session", read.bodyAsText().trim())

            val deleted = client.delete("/api/v1/files/session-created.txt") {
                header(RestSessionHeader, sessionId)
            }
            Assertions.assertEquals(HttpStatusCode.OK, deleted.status)
            val missing = client.get("/api/v1/files/session-created.txt") {
                header(RestSessionHeader, sessionId)
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
            val response = client.patch("/api/v1/files/plain.txt") {
                header(RestSessionHeader, sessionId)
                accept(ContentType.Application.Json)
                setBody(patch)
            }
            Assertions.assertEquals(HttpStatusCode.OK, response.status)

            val read = client.get("/api/v1/files/plain.txt") {
                header(RestSessionHeader, sessionId)
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
            val response = client.get("/api/v1/search/text/glob?query=RootFile&limit=10") {
                header(RestSessionHeader, sessionId)
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            val hits = json.parseToJsonElement(response.bodyAsText()).jsonObject["hits"]!!.jsonArray
            Assertions.assertTrue(hits.isNotEmpty(), response.bodyAsText())
        }
    }

    @Test
    fun `session search text route accepts project vfs url path`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val sessionId = client.createSessionId(projectPathFixture.get().resolve("glob").toString())
            val globUrl = encodedVfsUrl(projectPathFixture.get().resolve("glob"))
            val response = client.get("/api/v1/search/text/$globUrl?query=RootFile&limit=10") {
                header(RestSessionHeader, sessionId)
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            val hits = json.parseToJsonElement(response.bodyAsText()).jsonObject["hits"]!!.jsonArray
            Assertions.assertTrue(hits.isNotEmpty(), response.bodyAsText())
        }
    }

    @Test
    fun `session search text route rejects non indexed vfs url path`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val sessionId = client.createSessionId(projectPathFixture.get().resolve("glob").toString())
            val externalUrl = encodedVfsUrl(externalPathFixture.get())
            val response = client.get("/api/v1/search/text/$externalUrl?query=ExternalSearch&limit=10") {
                header(RestSessionHeader, sessionId)
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.Forbidden, response.status)
            Assertions.assertTrue(
                response.bodyAsText().contains("indexed dependency/SDK files"),
                response.bodyAsText(),
            )
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
            val response = client.post("/api/v1/navigation/glob/RootFile.kt") {
                header(RestSessionHeader, sessionId)
                contentType(ContentType.parse("text/x-patch"))
                accept(ContentType.Application.Json)
                setBody(body)
            }
            Assertions.assertTrue(response.status.isSuccess(), response.bodyAsText())
        }
    }

    @Test
    fun `session navigation route accepts project vfs url path`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val sessionId = client.createSessionId(projectPathFixture.get().resolve("glob").toString())
            val rootFileUrl = encodedVfsUrl(projectPathFixture.get().resolve("glob/RootFile.kt"))
            val body = """*** Goto:
@@
- class RootFile
+ XXXXXXXXXXXXXX
"""
            val response = client.post("/api/v1/navigation/$rootFileUrl") {
                header(RestSessionHeader, sessionId)
                contentType(ContentType.parse("text/x-patch"))
                accept(ContentType.Application.Json)
                setBody(body)
            }
            Assertions.assertTrue(response.status.isSuccess(), response.bodyAsText())
        }
    }

    @Test
    fun `session navigation route accepts non project vfs url path`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val sessionId = client.createSessionId(projectPathFixture.get().resolve("glob").toString())
            val externalUrl = encodedVfsUrl(externalPathFixture.get().resolve("External.kt"))
            val body = """*** Goto:
@@
- class External { fun ping() = "external needle" }
+ XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX
"""
            val response = client.post("/api/v1/navigation/$externalUrl") {
                header(RestSessionHeader, sessionId)
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

    private fun encodedVfsUrl(path: Path): String {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
            ?: error("missing test file: $path")
        return URLEncoder.encode(file.url, Charsets.UTF_8).replace("+", "%20")
    }
}
