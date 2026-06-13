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
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

@TestApplication
internal class NavigationRoutesTest {

    private val projectPathFixture = tempPathFixture()
    private val projectFixture = projectFixture(pathFixture = projectPathFixture, openAfterCreation = true)
    private val project by projectFixture
    private val moduleFixture = projectFixture.moduleFixture(name = "nav-test")
    private val contentRootFixture = moduleFixture.sourceRootFixture(
        pathFixture = projectFixture.pathInProjectFixture(Path.of("")),
    )
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setupFiles() {
        project
        val root = Path.of(requireNotNull(project.basePath))
        val srcDir = Files.createDirectories(root.resolve("src"))
        srcDir.resolve("Alpha.kt").writeText("class Alpha { fun hello() { println(\"hello world\") } }")
        srcDir.resolve("Beta.kt").writeText("class Beta : Alpha() { override fun hello() { println(\"beta hello\") } }")
        contentRootFixture.get().virtualFile.refresh(false, true)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    @Test
    fun `goto finds definition`() {
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val rootId = client.firstWorkspaceRootId(key, json)
            val body = """*** Goto:
@@
- class Alpha { fun hello() { println("hello world") } }
+ XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX
"""
            val response = client.post(navigationUrl(key, rootId, "src/Alpha.kt")) {
                contentType(ContentType.parse("text/x-patch"))
                setBody(body)
                accept(ContentType.Application.Json)
            }
            Assertions.assertTrue(response.status.isSuccess(), response.bodyAsText())
        }
    }

    @Test
    fun `usages finds references`() {
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val rootId = client.firstWorkspaceRootId(key, json)
            val body = """*** Usages:
@@
-    fun hello() { println("hello world") }
+    XXXXXXXXXX
"""
            val response = client.post(navigationUrl(key, rootId, "src/Alpha.kt")) {
                contentType(ContentType.parse("text/x-patch"))
                setBody(body)
                accept(ContentType.Application.Json)
            }
            Assertions.assertTrue(response.status.isSuccess(), response.bodyAsText())
        }
    }

    @Test
    fun `documentation returns element info`() {
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val rootId = client.firstWorkspaceRootId(key, json)
            val body = """*** Documentation:
@@
- class Alpha { fun hello() { println("hello world") } }
+ XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX
    """
            val response = client.post(navigationUrl(key, rootId, "src/Alpha.kt")) {
                contentType(ContentType.parse("text/x-patch"))
                setBody(body)
                accept(ContentType.Application.Json)
            }
            Assertions.assertTrue(response.status.isSuccess(), response.bodyAsText())
        }
    }

    @Test
    fun `empty body returns 400`() {
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val rootId = client.firstWorkspaceRootId(key, json)
            val response = client.post(navigationUrl(key, rootId, "src/Alpha.kt")) {
                contentType(ContentType.parse("text/x-patch"))
                setBody("")
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }
}
