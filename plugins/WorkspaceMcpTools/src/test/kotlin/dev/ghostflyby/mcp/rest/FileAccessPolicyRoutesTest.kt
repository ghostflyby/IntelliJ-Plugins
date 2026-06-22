/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.util.io.toCanonicalPath
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

@TestApplication
internal class FileAccessPolicyRoutesTest {
    private val projectPathFixture = tempPathFixture()
    private val projectFixture = projectFixture(pathFixture = projectPathFixture, openAfterCreation = true)
    private val project by projectFixture
    private val moduleFixture = projectFixture.moduleFixture(name = "file-access-policy-test")
    private val contentRootFixture = moduleFixture.sourceRootFixture(
        pathFixture = projectFixture.pathInProjectFixture(Path.of("")),
    )

    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setupFiles() {
        project
        val root = Path.of(requireNotNull(project.basePath))
        Files.writeString(root.resolve(".gitignore"), "*.generated")
        Files.createDirectories(root.resolve(".git"))
        Files.writeString(root.resolve(".git/config"), "[core]\n")
        Files.createDirectories(root.resolve("foo.git"))
        Files.writeString(root.resolve("foo.git/config"), "regular")
        Files.writeString(root.resolve("normal.txt"), "normal")
        Files.write(root.resolve("binary.bin"), byteArrayOf(0, 1, 2, 3))
        Files.writeString(root.resolve("ignored.generated"), "ignored")
        Files.writeString(root.resolve("visible.kt"), "class Visible")
        val excluded = VfsUtil.createDirectories(root.resolve("excluded").toCanonicalPath())
        Files.writeString(root.resolve("excluded/hidden.kt"), "class Hidden")
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/source.txt"), "source")

        Files.writeString(root.resolve("a_one.kt"), "class AOne")
        Files.writeString(root.resolve("b_two.kt"), "class BTwo")
        Files.writeString(root.resolve("c_three.kt"), "class CThree")
        ApplicationManager.getApplication().runWriteAction {
            val model = ModuleRootManager.getInstance(moduleFixture.get()).modifiableModel
            var committed = false
            try {
                val entry = model.contentEntries.first()
                entry.addExcludeFolder(excluded.url)
                model.commit()
                committed = true
            } finally {
                if (!committed) model.dispose()
            }
        }

        contentRootFixture.get().virtualFile.refresh(false, true)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    @Test
    fun `gitignore file alone does not classify file as ignored`() {

        restTestApplication {
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val meta = sessionClient.get(sessionClient.rootPathUrl("ignored.generated", meta = true)) {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, meta.status)
            val parsed = json.parseToJsonElement(meta.bodyAsText()).jsonObject
            Assertions.assertEquals("WORKSPACE_TEXT", parsed["classification"]?.jsonPrimitive?.content)

            val content = sessionClient.get(sessionClient.rootPathUrl("ignored.generated"))
            Assertions.assertEquals(HttpStatusCode.OK, content.status)
            Assertions.assertEquals("ignored", content.bodyAsText().trim())

            val structure = sessionClient.get(sessionClient.rootPathUrl("ignored.generated", structure = true))
            Assertions.assertEquals(HttpStatusCode.OK, structure.status)
        }
    }

    @Test
    fun `gitignore file alone does not require force for writes`() {

        restTestApplication {
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val denied = sessionClient.put(sessionClient.rootPathUrl("ignored.generated")) {
                setBody("changed")
            }
            Assertions.assertEquals(HttpStatusCode.OK, denied.status)

            val readBack = sessionClient.get(sessionClient.rootPathUrl("ignored.generated"))
            Assertions.assertEquals("changed", readBack.bodyAsText().trim())
        }
    }

    @Test
    fun `git metadata paths are read-only for writes`() {

        restTestApplication {
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)
            val root = projectPathFixture.get()

            val putExisting = sessionClient.put(sessionClient.rootPathUrl(".git/config")) {
                setBody("changed")
            }
            Assertions.assertEquals(HttpStatusCode.Forbidden, putExisting.status)
            Assertions.assertTrue(putExisting.bodyAsText().contains("Git metadata paths are read-only"))
            Assertions.assertEquals("[core]\n", root.resolve(".git/config").toFile().readText())

            val putMissing = sessionClient.put(sessionClient.rootPathUrl(".git/new-file")) {
                setBody("new")
            }
            Assertions.assertEquals(HttpStatusCode.Forbidden, putMissing.status)
            Assertions.assertFalse(Files.exists(root.resolve(".git/new-file")))

            val postDirectory = sessionClient.post(sessionClient.rootPathUrl(".git/new-dir")) {
                setBody("")
            }
            Assertions.assertEquals(HttpStatusCode.Forbidden, postDirectory.status)
            Assertions.assertFalse(Files.exists(root.resolve(".git/new-dir")))

            val force = sessionClient.put(sessionClient.rootPathUrl(".git/config", force = true)) {
                setBody("forced")
            }
            Assertions.assertEquals(HttpStatusCode.Forbidden, force.status)
            Assertions.assertTrue(force.bodyAsText().contains("force: true"))

            val dotGitSubstring = sessionClient.put(sessionClient.rootPathUrl("foo.git/config")) {
                setBody("changed")
            }
            Assertions.assertEquals(HttpStatusCode.OK, dotGitSubstring.status)
            Assertions.assertFalse(dotGitSubstring.bodyAsText().contains("Git metadata paths are read-only"))
        }
    }

    @Test
    fun `project content wins over library source classification for writes`() {

        restTestApplication {
            val root = Path.of(requireNotNull(project.basePath))
            val sourceRoot = VfsUtil.createDirectories(root.resolve("src").toCanonicalPath())

            ApplicationManager.getApplication().runWriteAction {
                val model = ModuleRootManager.getInstance(moduleFixture.get()).modifiableModel
                var committed = false
                try {
                    val library = model.moduleLibraryTable.createLibrary("overlapping-source-library")
                    library.modifiableModel.apply {
                        addRoot(sourceRoot, OrderRootType.SOURCES)
                        commit()
                    }
                    model.commit()
                    committed = true
                } finally {
                    if (!committed) model.dispose()
                }
            }
            IndexingTestUtil.waitUntilIndexesAreReady(project)

            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val meta = sessionClient.get(sessionClient.rootPathUrl("src/source.txt", meta = true)) {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, meta.status)
            val parsed = json.parseToJsonElement(meta.bodyAsText()).jsonObject
            Assertions.assertEquals("WORKSPACE_TEXT", parsed["classification"]?.jsonPrimitive?.content)
            val writableKinds = parsed["writableKinds"]?.let { it as JsonArray }
                ?.map { it.jsonPrimitive.content }
                ?.toSet()
                ?: emptySet()
            Assertions.assertTrue(setOf("put", "patch", "delete").all { it in writableKinds })

            val put = sessionClient.put(sessionClient.rootPathUrl("src/source.txt")) {
                setBody("changed source")
            }
            Assertions.assertEquals(HttpStatusCode.OK, put.status)

            val readBack = sessionClient.get(sessionClient.rootPathUrl("src/source.txt"))
            Assertions.assertEquals("changed source", readBack.bodyAsText().trim())
        }
    }

    @Test
    fun `binary files are GET only`() {

        restTestApplication {
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val meta = sessionClient.get(sessionClient.rootPathUrl("binary.bin", meta = true)) {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, meta.status)
            val parsed = json.parseToJsonElement(meta.bodyAsText()).jsonObject
            Assertions.assertEquals("WORKSPACE_BINARY", parsed["classification"]?.jsonPrimitive?.content)

            val put = sessionClient.put(sessionClient.rootPathUrl("binary.bin")) { setBody("nope") }
            Assertions.assertEquals(HttpStatusCode.UnsupportedMediaType, put.status)

            val patch = sessionClient.patch(sessionClient.rootPathUrl("binary.bin")) {
                setBody("*** Begin Patch\n*** End Patch")
            }
            Assertions.assertEquals(HttpStatusCode.UnsupportedMediaType, patch.status)

            val delete = sessionClient.delete(sessionClient.rootPathUrl("binary.bin"))
            Assertions.assertEquals(HttpStatusCode.UnsupportedMediaType, delete.status)
        }
    }

    @Test
    fun `excluded files are returned as not found`() {

        restTestApplication {
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val get = sessionClient.get(sessionClient.rootPathUrl("excluded/hidden.kt"))
            Assertions.assertEquals(HttpStatusCode.NotFound, get.status)

            val put = sessionClient.put(sessionClient.rootPathUrl("excluded/hidden.kt")) {
                setBody("changed")
            }
            Assertions.assertEquals(HttpStatusCode.Forbidden, put.status)

            val explicitFalse = sessionClient.put(sessionClient.rootPathUrl("excluded/hidden.kt", force = false)) {
                setBody("changed")
            }
            Assertions.assertEquals(HttpStatusCode.Forbidden, explicitFalse.status)
            Assertions.assertTrue(explicitFalse.bodyAsText().contains("force: false"))

            val explicitTrue = sessionClient.put(sessionClient.rootPathUrl("excluded/hidden.kt", force = true)) {
                setBody("changed")
            }
            Assertions.assertEquals(HttpStatusCode.Forbidden, explicitTrue.status)
            Assertions.assertTrue(explicitTrue.bodyAsText().contains("force: true"))
        }
    }

    @Test
    fun `glob silently filters excluded files`() {

        restTestApplication {
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response = sessionClient.get(globPathUrl("", glob = listOf("**/*.kt"))) {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, response.status)

            val body = response.bodyAsText()
            Assertions.assertTrue(body.contains("visible.kt"))
            Assertions.assertFalse(body.contains("excluded/hidden.kt"))
        }
    }

    @Test
    fun `glob limit caps returned paths`() {

        restTestApplication {
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val limited = sessionClient.get(globPathUrl("", glob = listOf("**/*.kt"), limit = 2)) {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, limited.status)
            val body = json.parseToJsonElement(limited.bodyAsText())
            Assertions.assertTrue(body is JsonArray, "expected array but got $body")
            Assertions.assertEquals(2, (body as JsonArray).size)
        }
    }
}
