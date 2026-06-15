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
internal class SearchTextRoutesTest {

    private val projectPathFixture = tempPathFixture()
    private val projectFixture = projectFixture(pathFixture = projectPathFixture, openAfterCreation = true)
    private val project by projectFixture
    private val moduleFixture = projectFixture.moduleFixture(name = "search-text-test")
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
        srcDir.resolve("Beta.kt").writeText("class Beta { fun world() { println(\"world\") } }")
        srcDir.resolve("Ignore.txt").writeText("hello world again")
        contentRootFixture.get().virtualFile.refresh(false, true)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    @Test
    fun `basic plain search returns hits`() {

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response = sessionClient.get(searchTextUrl("src", query = "hello")) {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            val hits = json.parseToJsonElement(response.bodyAsText()).jsonObject["hits"]!!.jsonArray
            Assertions.assertTrue(hits.isNotEmpty(), "expected at least 1 hit, got ${hits.size}")
        }
    }

    @Test
    fun `search defaults to markdown hit list`() {

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response = sessionClient.get(searchTextUrl("src", query = "hello"))
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            Assertions.assertEquals(TestMarkdownContentType, response.responseContentType())
            val body = response.bodyAsText()
            Assertions.assertTrue(body.contains("## Hits"), body)
            Assertions.assertTrue(body.contains("occurrenceId:"), body)
            Assertions.assertTrue(body.contains("src/Alpha.kt:1:"), body)
        }
    }

    @Test
    fun `search without query returns 400`() {

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response = sessionClient.get(searchTextUrl(query = "")) {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `search with file filter respects glob`() {

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response = sessionClient.get(searchTextUrl("src", query = "world", fileFilter = "*.kt")) {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `search can use a single file as range`() {

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response = sessionClient.get(searchTextUrl("src/Ignore.txt", query = "again")) {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            val hits = json.parseToJsonElement(response.bodyAsText()).jsonObject["hits"]!!.jsonArray
            Assertions.assertEquals(1, hits.size)
            Assertions.assertTrue(
                hits[0].jsonObject["filePath"]!!.jsonPrimitive.content.endsWith("src/Ignore.txt"),
            )
        }
    }

    @Test
    fun `search limit caps hits`() {

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response = sessionClient.get(searchTextUrl("src", query = "class", limit = 1)) {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            val hits = json.parseToJsonElement(response.bodyAsText()).jsonObject["hits"]!!.jsonArray
            Assertions.assertTrue(hits.isNotEmpty())
        }
    }

    @Test
    fun `hit includes occurrenceId and offsets`() {

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response = sessionClient.get(searchTextUrl("src", query = "hello")) {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            val hit = json.parseToJsonElement(response.bodyAsText()).jsonObject["hits"]!!.jsonArray[0].jsonObject
            Assertions.assertNotNull(hit["occurrenceId"]?.jsonPrimitive?.content)
            Assertions.assertTrue(hit["startOffset"]!!.jsonPrimitive.content.toInt() >= 0)
            Assertions.assertTrue(hit["endOffset"]!!.jsonPrimitive.content.toInt() > hit["startOffset"]!!.jsonPrimitive.content.toInt())
            Assertions.assertEquals(1, hit["lineNumber"]!!.jsonPrimitive.content.toInt())
        }
    }

    @Test
    fun `search text URL includes query params`() {
        val url = searchTextUrl("src", query = "hello", limit = 25)
        Assertions.assertTrue(url.contains("query=hello"), url)
        Assertions.assertTrue(url.contains("limit=25"), url)
    }
}
