package dev.ghostflyby.mcp.rest

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.*
import dev.ghostflyby.mcp.sdk.workspaceProjectKey
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.resources.Resources
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
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
        Files.createDirectories(root.resolve("excluded"))
        Files.writeString(root.resolve("excluded/hidden.txt"), "hidden")
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
    fun `ignored text is readable but structure is forbidden`() {
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val meta = client.get("/api/v1/projects/$key/files/ignored.generated?meta")
            Assertions.assertEquals(HttpStatusCode.OK, meta.status)
            val parsed = json.parseToJsonElement(meta.bodyAsText()).jsonObject
            Assertions.assertEquals("IGNORED_TEXT", parsed["classification"]?.jsonPrimitive?.content)

            val content = client.get("/api/v1/projects/$key/files/ignored.generated")
            Assertions.assertEquals(HttpStatusCode.OK, content.status)
            Assertions.assertEquals("ignored", content.bodyAsText().trim())

            val structure = client.get("/api/v1/projects/$key/files/ignored.generated?structure")
            Assertions.assertEquals(HttpStatusCode.Forbidden, structure.status)
        }
    }

    @Test
    fun `ignored text writes require force`() {
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val denied = client.put("/api/v1/projects/$key/files/ignored.generated") {
                setBody("changed")
            }
            Assertions.assertEquals(HttpStatusCode.Forbidden, denied.status)

            val forced = client.put("/api/v1/projects/$key/files/ignored.generated?force=true") {
                setBody("changed")
            }
            Assertions.assertEquals(HttpStatusCode.OK, forced.status)

            val readBack = client.get("/api/v1/projects/$key/files/ignored.generated")
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

            val meta = client.get("/api/v1/projects/$key/files/binary.bin?meta")
            Assertions.assertEquals(HttpStatusCode.OK, meta.status)
            val parsed = json.parseToJsonElement(meta.bodyAsText()).jsonObject
            Assertions.assertEquals("WORKSPACE_BINARY", parsed["classification"]?.jsonPrimitive?.content)

            val put = client.put("/api/v1/projects/$key/files/binary.bin") { setBody("nope") }
            Assertions.assertEquals(HttpStatusCode.UnsupportedMediaType, put.status)

            val patch = client.patch("/api/v1/projects/$key/files/binary.bin") {
                setBody("*** Begin Patch\n*** End Patch")
            }
            Assertions.assertEquals(HttpStatusCode.UnsupportedMediaType, patch.status)

            val delete = client.delete("/api/v1/projects/$key/files/binary.bin")
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

            val get = client.get("/api/v1/projects/$key/files/excluded/hidden.txt")
            Assertions.assertEquals(HttpStatusCode.NotFound, get.status)

            val put = client.put("/api/v1/projects/$key/files/excluded/hidden.txt") {
                setBody("changed")
            }
            Assertions.assertEquals(HttpStatusCode.Forbidden, put.status)
        }
    }
}
