package dev.ghostflyby.mcp.rest

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.*
import dev.ghostflyby.mcp.sdk.workspaceProjectKey
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.resources.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
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
        Files.writeString(root.resolve("normal.txt"), "normal")
        Files.write(root.resolve("binary.bin"), byteArrayOf(0, 1, 2, 3))
        Files.writeString(root.resolve("ignored.generated"), "ignored")
        Files.writeString(root.resolve("visible.kt"), "class Visible")
        Files.createDirectories(root.resolve("excluded"))
        Files.writeString(root.resolve("excluded/hidden.kt"), "class Hidden")
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/source.txt"), "source")

        val excluded = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve("excluded"))
            ?: error("excluded dir missing")
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
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val meta = client.get("${client.rootPathUrl(key, json, "ignored.generated")}?meta") {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, meta.status)
            val parsed = json.parseToJsonElement(meta.bodyAsText()).jsonObject
            Assertions.assertEquals("WORKSPACE_TEXT", parsed["classification"]?.jsonPrimitive?.content)

            val content = client.get(client.rootPathUrl(key, json, "ignored.generated"))
            Assertions.assertEquals(HttpStatusCode.OK, content.status)
            Assertions.assertEquals("ignored", content.bodyAsText().trim())

            val structure = client.get("${client.rootPathUrl(key, json, "ignored.generated")}?structure")
            Assertions.assertEquals(HttpStatusCode.OK, structure.status)
        }
    }

    @Test
    fun `gitignore file alone does not require force for writes`() {
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val denied = client.put(client.rootPathUrl(key, json, "ignored.generated")) {
                setBody("changed")
            }
            Assertions.assertEquals(HttpStatusCode.OK, denied.status)

            val readBack = client.get(client.rootPathUrl(key, json, "ignored.generated"))
            Assertions.assertEquals("changed", readBack.bodyAsText().trim())
        }
    }

    @Test
    fun `binary files are GET only`() {
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val meta = client.get("${client.rootPathUrl(key, json, "binary.bin")}?meta") {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, meta.status)
            val parsed = json.parseToJsonElement(meta.bodyAsText()).jsonObject
            Assertions.assertEquals("WORKSPACE_BINARY", parsed["classification"]?.jsonPrimitive?.content)

            val put = client.put(client.rootPathUrl(key, json, "binary.bin")) { setBody("nope") }
            Assertions.assertEquals(HttpStatusCode.UnsupportedMediaType, put.status)

            val patch = client.patch(client.rootPathUrl(key, json, "binary.bin")) {
                setBody("*** Begin Patch\n*** End Patch")
            }
            Assertions.assertEquals(HttpStatusCode.UnsupportedMediaType, patch.status)

            val delete = client.delete(client.rootPathUrl(key, json, "binary.bin"))
            Assertions.assertEquals(HttpStatusCode.UnsupportedMediaType, delete.status)
        }
    }

    @Test
    fun `excluded files are returned as not found`() {
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val get = client.get(client.rootPathUrl(key, json, "excluded/hidden.kt"))
            Assertions.assertEquals(HttpStatusCode.NotFound, get.status)

            val put = client.put(client.rootPathUrl(key, json, "excluded/hidden.kt")) {
                setBody("changed")
            }
            Assertions.assertEquals(HttpStatusCode.Forbidden, put.status)
        }
    }

    @Test
    fun `glob silently filters excluded files`() {
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val response = client.get("${client.rootPathUrl(key, json, "")}?glob=**/*.kt") {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, response.status)

            val body = response.bodyAsText()
            Assertions.assertTrue(body.contains("visible.kt"))
            Assertions.assertFalse(body.contains("excluded/hidden.kt"))
        }
    }
}
