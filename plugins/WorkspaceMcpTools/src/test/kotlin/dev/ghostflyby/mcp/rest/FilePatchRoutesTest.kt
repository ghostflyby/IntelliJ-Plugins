package dev.ghostflyby.mcp.rest

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
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

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

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val patch = """*** Begin Patch
*** Update File: plain.txt
@@
-hello sample
+hello patched
*** End Patch"""
            val resp = sessionClient.patch(sessionClient.rootPathUrl("plain.txt")) {
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                setBody(patch)
            }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)

            val getResp = sessionClient.get(sessionClient.rootPathUrl("plain.txt"))
            Assertions.assertEquals("hello patched", getResp.bodyAsText().trim())
            Assertions.assertEquals("hello patched", projectPathFixture.get().resolve("plain.txt").readText().trim())
        }
    }

    @Test
    fun `PATCH creates a new file via Add section`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val patch = """*** Begin Patch
*** Add File: patch-new.txt
+created via patch
*** End Patch"""
            // Create the parent dir first, then PATCH
            val resp = sessionClient.patch(sessionClient.rootPathUrl("patch-new.txt")) { setBody(patch) }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)
            Assertions.assertEquals(TestMarkdownContentType, resp.responseContentType())
            Assertions.assertTrue(resp.bodyAsText().contains("- add patch-new.txt"), resp.bodyAsText())

            val getResp = sessionClient.get(sessionClient.rootPathUrl("patch-new.txt"))
            Assertions.assertEquals("created via patch", getResp.bodyAsText().trim())
        }
    }

    @Test
    fun `root URL PATCH updates an existing workspace file`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val patch = """*** Begin Patch
*** Update File: plain.txt
@@
-hello sample
+hello root patched
*** End Patch"""
            val resp = sessionClient.patch(rootPathUrl("plain.txt")) { setBody(patch) }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)

            val getResp = sessionClient.get(rootPathUrl("plain.txt"))
            Assertions.assertEquals("hello root patched", getResp.bodyAsText().trim())
        }
    }

    @Test
    fun `PATCH on file target ignores section path`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val patch = """*** Begin Patch
*** Update File: other-file.txt
@@
-hello sample
+hello patched
*** End Patch"""
            val resp = sessionClient.patch(sessionClient.rootPathUrl("plain.txt")) {
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                setBody(patch)
            }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)
            val failed = json.parseToJsonElement(resp.bodyAsText()).jsonObject["failed"]?.jsonArray
            Assertions.assertTrue(failed.isNullOrEmpty())

            val getResp = sessionClient.get(sessionClient.rootPathUrl("plain.txt"))
            Assertions.assertEquals("hello patched", getResp.bodyAsText().trim())
        }
    }

    // ── Directory PATCH (multi-file) ───────────────────────

    @Test
    fun `PATCH with git diff format updates existing file`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val diff = """diff --git a/plain.txt b/plain.txt
--- a/plain.txt
+++ b/plain.txt
@@ -1 +1 @@
-hello sample
+hello git-patched"""
            val resp = sessionClient.patch(sessionClient.rootPathUrl("plain.txt")) {
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                setBody(diff)
            }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)
            val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            Assertions.assertEquals(1, body["applied"]?.jsonArray?.size)
            Assertions.assertTrue(body["failed"]?.jsonArray.isNullOrEmpty())

            val getResp = sessionClient.get(sessionClient.rootPathUrl("plain.txt"))
            Assertions.assertEquals("hello git-patched", getResp.bodyAsText().trim())
        }
    }

    @Test
    fun `PATCH with git diff format ignores path for file target`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val diff = """diff --git a/other-file.txt b/other-file.txt
--- a/other-file.txt
+++ b/other-file.txt
@@ -1 +1 @@
-hello sample
+hello git-patched"""
            val resp = sessionClient.patch(sessionClient.rootPathUrl("plain.txt")) {
                accept(ContentType.Application.Json)
                setBody(diff)
            }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)
            val failed = json.parseToJsonElement(resp.bodyAsText()).jsonObject["failed"]?.jsonArray
            Assertions.assertTrue(failed.isNullOrEmpty())

            val getResp = sessionClient.get(sessionClient.rootPathUrl("plain.txt"))
            Assertions.assertEquals("hello git-patched", getResp.bodyAsText().trim())
        }
    }

    @Test
    fun `PATCH with text-x-patch content-type requires git format`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val diff = """diff --git a/plain.txt b/plain.txt
--- a/plain.txt
+++ b/plain.txt
@@ -1 +1 @@
-hello sample
+hello git-patched"""
            val resp = sessionClient.patch(sessionClient.rootPathUrl("plain.txt")) {
                header("Content-Type", "text/x-patch")
                setBody(diff)
            }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)
        }
    }

    @Test
    fun `PATCH with text-x-patch rejects invalid git format`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val resp = sessionClient.patch(sessionClient.rootPathUrl("plain.txt")) {
                header("Content-Type", "text/x-patch")
                setBody("*** Begin Patch")
            }
            Assertions.assertEquals(HttpStatusCode.BadRequest, resp.status)
            Assertions.assertEquals(TestMarkdownContentType, resp.responseContentType())
            Assertions.assertTrue(resp.bodyAsText().contains("ERROR: Unrecognized patch format"), resp.bodyAsText())
        }
    }

    @Test
    fun `PATCH with git diff format creates new file`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val diff = """diff --git a/newfile.txt b/newfile.txt
new file mode 100644
--- /dev/null
+++ b/newfile.txt
@@ -0,0 +1 @@
+created by git patch"""
            val resp = sessionClient.patch(sessionClient.rootPathUrl("newfile.txt")) {
                setBody(diff)
            }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)
        }
    }

    @Test
    fun `PATCH with git diff format deletes file`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val diff = """diff --git a/plain.txt b/plain.txt
deleted file mode 100644
--- a/plain.txt
+++ /dev/null
@@ -1 +0,0 @@
-hello sample"""
            val resp = sessionClient.patch(sessionClient.rootPathUrl("plain.txt")) {
                setBody(diff)
            }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)

            val getResp = sessionClient.get(sessionClient.rootPathUrl("plain.txt"))
            Assertions.assertEquals(HttpStatusCode.NotFound, getResp.status)
        }
    }

    @Test
    fun `PATCH on directory allows multi-file operations`() {
        project
        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val patch = """*** Begin Patch
*** Update File: foo.xml
@@
-<foo/>
+<root><child/></root>
*** Add File: newfile.txt
+created under directory
*** End Patch"""
            val resp = sessionClient.patch(sessionClient.rootPathUrl("src")) { setBody(patch) }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)
            val text = resp.bodyAsText()
            Assertions.assertTrue(text.contains("foo.xml"))
            Assertions.assertTrue(text.contains("newfile.txt"))
        }
    }

    @Test
    fun `PATCH on directory still uses section paths`() {
        project
        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val patch = """*** Begin Patch
*** Update File: foo.xml
@@
-<foo/>
+<root><child/></root>
*** End Patch"""
            val resp = sessionClient.patch(sessionClient.rootPathUrl("src")) { setBody(patch) }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)

            val foo = sessionClient.get(sessionClient.rootPathUrl("src/foo.xml"))
            Assertions.assertEquals("<root><child/></root>", foo.bodyAsText().trim())
            val bar = sessionClient.get(sessionClient.rootPathUrl("src/bar.xml"))
            Assertions.assertEquals("<bar/>", bar.bodyAsText().trim())
        }
    }

    @Test
    fun `PATCH supports standard move section`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val patch = """*** Begin Patch
*** Update File: bar.xml
*** Move to: moved-bar.xml
*** End Patch"""
            val resp = sessionClient.patch(sessionClient.rootPathUrl("src")) { setBody(patch) }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.bodyAsText()
            Assertions.assertFalse(body.contains("Read access is allowed"), body)
            Assertions.assertFalse(body.contains("failed:"), body)

            val moved = sessionClient.get(sessionClient.rootPathUrl("src/moved-bar.xml"))
            Assertions.assertEquals("<bar/>", moved.bodyAsText().trim())

            val old = sessionClient.get(sessionClient.rootPathUrl("src/bar.xml"))
            Assertions.assertEquals(HttpStatusCode.NotFound, old.status)
        }
    }

    @Test
    fun `PATCH supports move and rename through refactoring`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val patch = """*** Begin Patch
*** Update File: bar.xml
*** Move to: moved/bar-renamed.xml
*** End Patch"""
            val resp = sessionClient.patch(sessionClient.rootPathUrl("src")) { setBody(patch) }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)
            Assertions.assertTrue(resp.bodyAsText().contains("- update src/bar.xml"), resp.bodyAsText())

            val moved = sessionClient.get(sessionClient.rootPathUrl("src/moved/bar-renamed.xml"))
            Assertions.assertEquals("<bar/>", moved.bodyAsText().trim())

            val old = sessionClient.get(sessionClient.rootPathUrl("src/bar.xml"))
            Assertions.assertEquals(HttpStatusCode.NotFound, old.status)
        }
    }

    @Test
    fun `PATCH move to git metadata path is rejected`() {
        project
        Files.createDirectories(projectPathFixture.get().resolve("src/.git"))
        contentRootFixture.get().virtualFile.refresh(false, true)
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val patch = """*** Begin Patch
*** Update File: bar.xml
*** Move to: .git/moved.xml
*** End Patch"""
            val resp = sessionClient.patch(sessionClient.rootPathUrl("src")) { setBody(patch) }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.bodyAsText()
            Assertions.assertTrue(body.contains("Git metadata paths are read-only"), body)

            val original = sessionClient.get(sessionClient.rootPathUrl("src/bar.xml"))
            Assertions.assertEquals(HttpStatusCode.OK, original.status)
            Assertions.assertEquals("<bar/>", original.bodyAsText().trim())

            Assertions.assertFalse(Files.exists(projectPathFixture.get().resolve("src/.git/moved.xml")))
        }
    }

    @Test
    fun `PATCH section targeting git metadata path is rejected`() {
        project
        Files.createDirectories(projectPathFixture.get().resolve("src/.git"))
        Files.writeString(projectPathFixture.get().resolve("src/.git/config"), "[core]\n")
        contentRootFixture.get().virtualFile.refresh(false, true)
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val patch = """*** Begin Patch
*** Update File: .git/config
@@
-[core]
+changed
*** End Patch"""
            val resp = sessionClient.patch(sessionClient.rootPathUrl("src")) { setBody(patch) }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.bodyAsText()
            Assertions.assertTrue(body.contains("Git metadata paths are read-only"), body)
            Assertions.assertEquals("[core]\n", projectPathFixture.get().resolve("src/.git/config").readText())
        }
    }

    @Test
    fun `PATCH move uses headless refactoring without override-only processor`() {
        project
        val srcDir = Files.createDirectories(projectPathFixture.get().resolve("src/pkg"))
        srcDir.resolve("Alpha.java").writeText(
            """
            package pkg;

            public class Alpha {
                public String name() {
                    return "alpha";
                }
            }
            """.trimIndent(),
        )
        srcDir.resolve("Beta.java").writeText(
            """
            package pkg;

            public class Beta {
                public String call(Alpha target) {
                    return target.name();
                }
            }
            """.trimIndent(),
        )
        contentRootFixture.get().virtualFile.refresh(false, true)
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val patch = """*** Begin Patch
*** Update File: pkg/Alpha.java
*** Move to: moved/Alpha.java
*** End Patch"""
            val resp = sessionClient.patch(sessionClient.rootPathUrl("src")) { setBody(patch) }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)
            Assertions.assertFalse(resp.bodyAsText().contains("failed:"), resp.bodyAsText())

            val moved = sessionClient.get(sessionClient.rootPathUrl("src/moved/Alpha.java"))
            Assertions.assertEquals(HttpStatusCode.OK, moved.status)
            Assertions.assertTrue(moved.bodyAsText().contains("public class Alpha"), moved.bodyAsText())

            val old = sessionClient.get(sessionClient.rootPathUrl("src/pkg/Alpha.java"))
            Assertions.assertEquals(HttpStatusCode.NotFound, old.status)

            val beta = sessionClient.get(sessionClient.rootPathUrl("src/pkg/Beta.java"))
            Assertions.assertEquals(HttpStatusCode.OK, beta.status)
            val betaText = beta.bodyAsText()
            Assertions.assertTrue(betaText.contains("public String call(Alpha target)"), betaText)
        }
    }

    @Test
    fun `PATCH supports workspace reformat operation`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val patch = """*** Begin Patch
*** Reformat File: foo.xml
*** End Patch"""
            val resp = sessionClient.patch(sessionClient.rootPathUrl("src")) { setBody(patch) }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.bodyAsText()
            Assertions.assertTrue(body.contains("- reformat src/foo.xml"), body)
        }
    }

    @Test
    fun `PATCH supports workspace cleanup operation`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val patch = """*** Begin Patch
*** Cleanup: foo.xml
*** End Patch"""
            val resp = sessionClient.patch(sessionClient.rootPathUrl("src")) { setBody(patch) }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.bodyAsText()
            Assertions.assertTrue(body.contains("- cleanup src/foo.xml"), body)
        }
    }

    @Test
    fun `PATCH applies workspace operations in stable order per file`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val patch = """*** Begin Patch
*** Reformat File: foo.xml
*** Optimize Imports: foo.xml
*** Cleanup: foo.xml
*** End Patch"""
            val resp = sessionClient.patch(sessionClient.rootPathUrl("src")) { setBody(patch) }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.bodyAsText()
            val cleanup = body.indexOf("- cleanup src/foo.xml")
            val optimizeImports = body.indexOf("- optimize-imports src/foo.xml")
            val reformat = body.indexOf("- reformat src/foo.xml")

            Assertions.assertTrue(cleanup >= 0, body)
            Assertions.assertTrue(optimizeImports > cleanup, body)
            Assertions.assertTrue(reformat > optimizeImports, body)
        }
    }

    @Test
    fun `PATCH ignores indented workspace operation marker text`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val create = """*** Begin Patch
*** Add File: operation-doc.md
+before
+  *** Cleanup: src/B.kt
+after
*** End Patch"""
            val createResp = sessionClient.patch(sessionClient.rootPathUrl("operation-doc.md")) { setBody(create) }
            Assertions.assertEquals(HttpStatusCode.OK, createResp.status)

            val update = """*** Begin Patch
*** Update File: operation-doc.md
@@
 before
   *** Cleanup: src/B.kt
-after
+after updated
*** End Patch"""
            val resp = sessionClient.patch(sessionClient.rootPathUrl("operation-doc.md")) { setBody(update) }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.bodyAsText()
            Assertions.assertFalse(body.contains("File not found: src/B.kt"), body)

            val getResp = sessionClient.get(sessionClient.rootPathUrl("operation-doc.md"))
            Assertions.assertEquals(
                """before
  *** Cleanup: src/B.kt
after updated""",
                getResp.bodyAsText().trim(),
            )
        }
    }

    @Test
    fun `PATCH problemFix query reports unsupported public API`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val resp = sessionClient.patch("${sessionClient.rootPathUrl("plain.txt")}?problemFix=true") {
                setBody(
                    """*** Begin Patch
*** Fix Problem
@@
-hello sample
+XXXXXXXXXXXX
*** End Patch""",
                )
            }
            Assertions.assertEquals(HttpStatusCode.Conflict, resp.status)
            Assertions.assertTrue(resp.bodyAsText().contains("Problem fixes are not supported"), resp.bodyAsText())
        }
    }

    @Test
    fun `PATCH on directory rejects section with bad hunk`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val patch = """*** Begin Patch
*** Update File: nonexistent.xml
@@
 -nothing
+something
*** End Patch"""
            val resp = sessionClient.patch(sessionClient.rootPathUrl("src")) { setBody(patch) }
            Assertions.assertEquals(HttpStatusCode.OK, resp.status)
            val text = resp.bodyAsText()
            Assertions.assertTrue(text.contains("failed"))
        }
    }

    @Test
    fun `PATCH with unknown format returns 400`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val resp = sessionClient.patch(sessionClient.rootPathUrl("plain.txt")) {
                setBody("this is not a patch")
            }
            Assertions.assertEquals(HttpStatusCode.BadRequest, resp.status)
        }
    }

    @Test
    fun `PATCH reports problems for broken XML file`() {
        project

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val patch = """*** Begin Patch
*** Add File: broken.xml
+<root>
+    <unclosed
*** End Patch"""
            val resp = sessionClient.patch(sessionClient.rootPathUrl("broken.xml")) {
                setBody(patch)
            }
            Assertions.assertTrue(resp.bodyAsText().contains("applied:"), "PATCH should succeed")
        }
    }
}
