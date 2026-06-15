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
import kotlinx.serialization.json.jsonPrimitive
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
        srcDir.resolve("Alpha.java").writeText(
            """
            public class Alpha {
                /** Greets from Alpha. */
                public String greet() { return "hello"; }
            }
            """.trimIndent(),
        )
        srcDir.resolve("Beta.java").writeText(
            """
            public class Beta {
                public String call(Alpha target) {
                    return target.greet();
                }
            }
            """.trimIndent(),
        )
        contentRootFixture.get().virtualFile.refresh(false, true)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    @Test
    fun `goto finds definition`() {


        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val body = """*** Goto:
@@
-        return target.greet();
+        return target.XXXXX();
"""
            val response = sessionClient.post(navigationUrl("src/Beta.java")) {
                contentType(ContentType.parse("text/x-patch"))
                setBody(body)
                accept(ContentType.Application.Json)
            }
            Assertions.assertTrue(response.status.isSuccess(), response.bodyAsText())
            val target = json.parseToJsonElement(response.bodyAsText())
                .jsonObject["applied"]!!
                .jsonArray
                .first()
                .jsonObject["result"]!!
                .jsonObject
            val fileUrl = target["fileUrl"]!!.jsonPrimitive.content
            Assertions.assertEquals(encodeRoutePathSegment(fileUrl), target["encodedFileUrl"]!!.jsonPrimitive.content)
            Assertions.assertTrue(
                fileUrl.endsWith("src/Alpha.java") || fileUrl.endsWith("src/Beta.java"),
                response.bodyAsText(),
            )
            Assertions.assertFalse(response.bodyAsText().contains("FAILED"), response.bodyAsText())
        }
    }

    @Test
    fun `usages finds references`() {


        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val body = """*** Usages:
@@
-    public String greet() { return "hello"; }
+    public String XXXXX() { return "hello"; }
"""
            val response = sessionClient.post(navigationUrl("src/Alpha.java")) {
                contentType(ContentType.parse("text/x-patch"))
                setBody(body)
                accept(ContentType.Application.Json)
            }
            Assertions.assertTrue(response.status.isSuccess(), response.bodyAsText())
            Assertions.assertTrue(
                json.parseToJsonElement(response.bodyAsText()).jsonObject["appliedUsages"]!!.jsonArray.isNotEmpty(),
                response.bodyAsText(),
            )
            Assertions.assertFalse(response.bodyAsText().contains("FAILED"), response.bodyAsText())
        }
    }

    @Test
    fun `documentation returns element info`() {


        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val body = """*** Documentation:
@@
-    public String greet() { return "hello"; }
+    public String XXXXX() { return "hello"; }
    """
            val response = sessionClient.post(navigationUrl("src/Alpha.java")) {
                contentType(ContentType.parse("text/x-patch"))
                setBody(body)
                accept(ContentType.Application.Json)
            }
            Assertions.assertTrue(response.status.isSuccess(), response.bodyAsText())
            val docs = json.parseToJsonElement(response.bodyAsText())
                .jsonObject["appliedDocs"]!!
                .jsonArray
            Assertions.assertTrue(docs.isNotEmpty(), response.bodyAsText())
            Assertions.assertFalse(response.bodyAsText().contains("FAILED"), response.bodyAsText())
        }
    }

    @Test
    fun `navigation accepts standard apply patch line prefixes`() {


        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val body = """*** Documentation:
@@
-public class Alpha {
+XXXXXXXXXXXXXXXXXXXX
"""
            val response = sessionClient.post(navigationUrl("src/Alpha.java")) {
                contentType(ContentType.parse("text/x-patch"))
                setBody(body)
                accept(ContentType.Application.Json)
            }
            Assertions.assertTrue(response.status.isSuccess(), response.bodyAsText())
            Assertions.assertTrue(
                json.parseToJsonElement(response.bodyAsText()).jsonObject["appliedDocs"]!!.jsonArray.isNotEmpty(),
                response.bodyAsText(),
            )
        }
    }

    @Test
    fun `empty body returns 400`() {


        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response = sessionClient.post(navigationUrl("src/Alpha.java")) {
                contentType(ContentType.parse("text/x-patch"))
                setBody("")
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }
}
