package dev.ghostflyby.mcp.rest

import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.*
import dev.ghostflyby.mcp.sdk.workspaceProjectKey
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.resources.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

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
    private val json = Json { ignoreUnknownKeys = true }

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
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val patch = """*** Begin Patch
*** Update File: plain.txt
@@
-hello sample
+hello patched
*** End Patch"""
            val resp = client.patch(client.rootPathUrl(key, json, "plain.txt")) {
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                setBody(patch)
            }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)

            val getResp = client.get(client.rootPathUrl(key, json, "plain.txt"))
            Assertions.assertEquals("hello patched", getResp.bodyAsText().trim())
            Assertions.assertEquals("hello patched", projectPathFixture.get().resolve("plain.txt").readText().trim())
        }
    }

    @Test
    fun `PATCH creates a new file via Add section`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val patch = """*** Begin Patch
*** Add File: patch-new.txt
+created via patch
*** End Patch"""
            // Create the parent dir first, then PATCH
            val resp = client.patch(client.rootPathUrl(key, json, "patch-new.txt")) { setBody(patch) }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)

            val getResp = client.get(client.rootPathUrl(key, json, "patch-new.txt"))
            Assertions.assertEquals("created via patch", getResp.bodyAsText().trim())
        }
    }

    @Test
    fun `root URL PATCH updates an existing workspace file`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val rootId = client.firstWorkspaceRootId(key, json)
            val patch = """*** Begin Patch
*** Update File: plain.txt
@@
-hello sample
+hello root patched
*** End Patch"""
            val resp = client.patch(rootPathUrl(key, rootId, "plain.txt")) { setBody(patch) }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)

            val getResp = client.get(rootPathUrl(key, rootId, "plain.txt"))
            Assertions.assertEquals("hello root patched", getResp.bodyAsText().trim())
        }
    }

    @Test
    fun `PATCH on file target ignores section path`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val patch = """*** Begin Patch
*** Update File: other-file.txt
@@
-hello sample
+hello patched
*** End Patch"""
            val resp = client.patch(client.rootPathUrl(key, json, "plain.txt")) {
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                setBody(patch)
            }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)
            Assertions.assertTrue(json.parseToJsonElement(resp.bodyAsText()).jsonObject["failed"]!!.jsonArray.isEmpty())

            val getResp = client.get(client.rootPathUrl(key, json, "plain.txt"))
            Assertions.assertEquals("hello patched", getResp.bodyAsText().trim())
        }
    }

    // ── Directory PATCH (multi-file) ───────────────────────

    @Test
    fun `PATCH with git diff format updates existing file`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val diff = """diff --git a/plain.txt b/plain.txt
--- a/plain.txt
+++ b/plain.txt
@@ -1 +1 @@
-hello sample
+hello git-patched"""
            val resp = client.patch(client.rootPathUrl(key, json, "plain.txt")) {
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                setBody(diff)
            }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)

            val getResp = client.get(client.rootPathUrl(key, json, "plain.txt"))
            Assertions.assertEquals("hello git-patched", getResp.bodyAsText().trim())
        }
    }

    @Test
    fun `PATCH with git diff format ignores path for file target`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val diff = """diff --git a/other-file.txt b/other-file.txt
--- a/other-file.txt
+++ b/other-file.txt
@@ -1 +1 @@
-hello sample
+hello git-patched"""
            val resp = client.patch(client.rootPathUrl(key, json, "plain.txt")) {
                accept(ContentType.Application.Json)
                setBody(diff)
            }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)
            Assertions.assertTrue(json.parseToJsonElement(resp.bodyAsText()).jsonObject["failed"]!!.jsonArray.isEmpty())

            val getResp = client.get(client.rootPathUrl(key, json, "plain.txt"))
            Assertions.assertEquals("hello git-patched", getResp.bodyAsText().trim())
        }
    }

    @Test
    fun `PATCH with text-x-patch content-type requires git format`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val diff = """diff --git a/plain.txt b/plain.txt
--- a/plain.txt
+++ b/plain.txt
@@ -1 +1 @@
-hello sample
+hello git-patched"""
            val resp = client.patch(client.rootPathUrl(key, json, "plain.txt")) {
                header("Content-Type", "text/x-patch")
                setBody(diff)
            }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)
        }
    }

    @Test
    fun `PATCH with text-x-patch rejects invalid git format`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val resp = client.patch(client.rootPathUrl(key, json, "plain.txt")) {
                header("Content-Type", "text/x-patch")
                setBody("*** Begin Patch")
            }
            Assertions.assertEquals(HttpStatusCode.BadRequest, resp.status)
        }
    }

    @Test
    fun `PATCH with git diff format creates new file`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val diff = """diff --git a/newfile.txt b/newfile.txt
new file mode 100644
--- /dev/null
+++ b/newfile.txt
@@ -0,0 +1 @@
+created by git patch"""
            val resp = client.patch(client.rootPathUrl(key, json, "newfile.txt")) {
                setBody(diff)
            }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)
        }
    }

    @Test
    fun `PATCH with git diff format deletes file`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val diff = """diff --git a/plain.txt b/plain.txt
deleted file mode 100644
--- a/plain.txt
+++ /dev/null
@@ -1 +0,0 @@
-hello sample"""
            val resp = client.patch(client.rootPathUrl(key, json, "plain.txt")) {
                setBody(diff)
            }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)

            val getResp = client.get(client.rootPathUrl(key, json, "plain.txt"))
            Assertions.assertEquals(HttpStatusCode.NotFound, getResp.status)
        }
    }

    @Test
    fun `PATCH on directory allows multi-file operations`() {
        project
        val key = workspaceProjectKey(project)
        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val patch = """*** Begin Patch
*** Update File: foo.xml
@@
-<foo/>
+<root><child/></root>
*** Add File: newfile.txt
+created under directory
*** End Patch"""
            val resp = client.patch(client.rootPathUrl(key, json, "src")) { setBody(patch) }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)
            val text = resp.bodyAsText()
            Assertions.assertTrue(text.contains("foo.xml"))
            Assertions.assertTrue(text.contains("newfile.txt"))
        }
    }

    @Test
    fun `PATCH on directory still uses section paths`() {
        project
        val key = workspaceProjectKey(project)
        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val patch = """*** Begin Patch
*** Update File: foo.xml
@@
-<foo/>
+<root><child/></root>
*** End Patch"""
            val resp = client.patch(client.rootPathUrl(key, json, "src")) { setBody(patch) }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)

            val foo = client.get(client.rootPathUrl(key, json, "src/foo.xml"))
            Assertions.assertEquals("<root><child/></root>", foo.bodyAsText().trim())
            val bar = client.get(client.rootPathUrl(key, json, "src/bar.xml"))
            Assertions.assertEquals("<bar/>", bar.bodyAsText().trim())
        }
    }

    @Test
    fun `PATCH on directory rejects section with bad hunk`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val patch = """*** Begin Patch
*** Update File: nonexistent.xml
@@
 -nothing
+something
*** End Patch"""
            val resp = client.patch(client.rootPathUrl(key, json, "src")) { setBody(patch) }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)
            val text = resp.bodyAsText()
            Assertions.assertTrue(text.contains("failed"))
        }
    }
    @Test
    fun `PATCH with unknown format returns 400`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val resp = client.patch(client.rootPathUrl(key, json, "plain.txt")) {
                setBody("this is not a patch")
            }
            Assertions.assertEquals(HttpStatusCode.BadRequest, resp.status)
        }
    }
}
