package dev.ghostflyby.mcp.rest

import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.*
import dev.ghostflyby.mcp.sdk.workspaceProjectKey
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.resources.Resources
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Path

@TestApplication
internal class FilePatchRoutesTest {

    private val projectPathFixture = tempPathFixture()
    private val projectFixture = projectFixture(pathFixture = projectPathFixture, openAfterCreation = true)
    private val project by projectFixture
    private val moduleFixture = projectFixture.moduleFixture(name = "patch-test")
    private val blueprint = Path.of(
        requireNotNull(javaClass.getResource("/fileContentIntegration")).toURI(),
    )
    private val contentRootFixture = moduleFixture.sourceRootFixture(
        pathFixture = projectFixture.pathInProjectFixture(Path.of("")),
        blueprintResourcePath = blueprint,
    )

    @BeforeEach
    fun refresh() {
        contentRootFixture.get().virtualFile.refresh(false, true)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    // ── Single-file PATCH ──────────────────────────────────

    @Test
    fun `PATCH updates an existing file`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val patch = """*** Begin Patch
*** Update File: plain.txt
@@
-hello sample
+hello patched
*** End Patch"""
            val resp = client.patch("/api/v1/projects/$key/files/plain.txt") { setBody(patch) }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)

            val getResp = client.get("/api/v1/projects/$key/files/plain.txt")
            Assertions.assertEquals("hello patched", getResp.bodyAsText().trim())
        }
    }

    @Test
    fun `PATCH creates a new file via Add section`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val patch = """*** Begin Patch
*** Add File: patch-new.txt
+created via patch
*** End Patch"""
            // Create the parent dir first, then PATCH
            val resp = client.patch("/api/v1/projects/$key/files/patch-new.txt") { setBody(patch) }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)

            val getResp = client.get("/api/v1/projects/$key/files/patch-new.txt")
            Assertions.assertEquals("created via patch", getResp.bodyAsText().trim())
        }
    }

    @Test
    fun `PATCH refuses section targeting different file`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val patch = """*** Begin Patch
*** Update File: other-file.txt
@@
-hello sample
+hello patched
*** End Patch"""
            val resp = client.patch("/api/v1/projects/$key/files/plain.txt") { setBody(patch) }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)
            val text = resp.bodyAsText()
            Assertions.assertTrue(text.contains("other-file.txt"))
            Assertions.assertTrue(text.contains("failed"))
        }
    }

    // ── Directory PATCH (multi-file) ───────────────────────

    @Test
    fun `PATCH on directory allows multi-file operations`() {
        project
        val key = workspaceProjectKey(project)
        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val patch = """*** Begin Patch
*** Update File: foo.xml
@@
-<root/>
+<root><child/></root>
*** Add File: newfile.txt
+created under directory
*** End Patch"""
            val resp = client.patch("/api/v1/projects/$key/files/src") { setBody(patch) }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)
            val text = resp.bodyAsText()
            Assertions.assertTrue(text.contains("foo.xml"))
            Assertions.assertTrue(text.contains("newfile.txt"))
        }
    }

    @Test
    fun `PATCH on directory rejects section with bad hunk`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            install(ContentNegotiation) { json() }
            install(Resources)
            routing { restApi() }

            val patch = """*** Begin Patch
*** Update File: nonexistent.xml
@@
 -nothing
+something
*** End Patch"""
            val resp = client.patch("/api/v1/projects/$key/files/src") { setBody(patch) }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)
            val text = resp.bodyAsText()
            Assertions.assertTrue(text.contains("failed"))
        }
    }
}
