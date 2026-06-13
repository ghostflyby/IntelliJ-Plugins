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
import java.nio.file.Path

@TestApplication
internal class FileWriteRoutesTest {

    private val projectPathFixture = tempPathFixture()
    private val projectFixture = projectFixture(pathFixture = projectPathFixture, openAfterCreation = true)
    private val project by projectFixture
    private val moduleFixture = projectFixture.moduleFixture(name = "write-test")
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

    // ── POST create new ──────────────────────────────────────

    @Test
    fun `POST creates a new file`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response = sessionClient.post(sessionClient.rootPathUrl(key, json, "new.txt")) {
                setBody("fresh content")
            }
            Assertions.assertEquals(HttpStatusCode.Created, response.status)

            // Verify file exists via GET
            val getResp = sessionClient.get(sessionClient.rootPathUrl(key, json, "new.txt"))
            Assertions.assertEquals(HttpStatusCode.OK, getResp.status)
            Assertions.assertEquals("fresh content", getResp.bodyAsText().trim())
        }
    }

    @Test
    fun `POST creates a directory with empty body`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response = sessionClient.post(sessionClient.rootPathUrl(key, json, "newdir")) {
                setBody("")
            }
            Assertions.assertEquals(HttpStatusCode.Created, response.status)
        }
    }

    @Test
    fun `POST on existing file returns 409`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            // plain.txt exists from blueprint
            val response = sessionClient.post(sessionClient.rootPathUrl(key, json, "plain.txt")) {
                setBody("overwrite attempt")
            }
            Assertions.assertEquals(HttpStatusCode.Conflict, response.status)
        }
    }

    // ── PUT create or replace ────────────────────────────────

    @Test
    fun `PUT creates a new file`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response = sessionClient.put(sessionClient.rootPathUrl(key, json, "put-created.txt")) {
                setBody("put content")
            }
            Assertions.assertEquals(HttpStatusCode.Created, response.status)
        }
    }

    @Test
    fun `PUT replaces existing file content`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response = sessionClient.put(sessionClient.rootPathUrl(key, json, "plain.txt")) {
                setBody("replaced content")
            }
            Assertions.assertEquals(HttpStatusCode.OK, response.status)

            val getResp = sessionClient.get(sessionClient.rootPathUrl(key, json, "plain.txt"))
            Assertions.assertEquals("replaced content", getResp.bodyAsText().trim())
        }
    }

    @Test
    fun `root URL supports PUT POST and DELETE for workspace text files`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val rootId = client.firstWorkspaceRootId(key, json)
            val put = sessionClient.put(rootPathUrl(key, rootId, "plain.txt")) {
                setBody("root replaced")
            }
            Assertions.assertEquals(HttpStatusCode.OK, put.status)
            Assertions.assertEquals(
                "root replaced",
                sessionClient.get(rootPathUrl(key, rootId, "plain.txt")).bodyAsText().trim(),
            )

            val post = sessionClient.post(rootPathUrl(key, rootId, "root-created.txt")) {
                setBody("root created")
            }
            Assertions.assertEquals(HttpStatusCode.Created, post.status)

            val delete = sessionClient.delete(rootPathUrl(key, rootId, "root-created.txt"))
            Assertions.assertEquals(HttpStatusCode.OK, delete.status)
        }
    }

    // ── DELETE ───────────────────────────────────────────────

    @Test
    fun `DELETE removes an existing file`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response = sessionClient.delete(sessionClient.rootPathUrl(key, json, "plain.txt"))
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            Assertions.assertEquals("true", response.bodyAsText())

            val getResp = sessionClient.get(sessionClient.rootPathUrl(key, json, "plain.txt"))
            Assertions.assertEquals(HttpStatusCode.NotFound, getResp.status)
        }
    }

    @Test
    fun `DELETE missing file returns 404`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response = sessionClient.delete(sessionClient.rootPathUrl(key, json, "doesnotexist.txt"))
            Assertions.assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `DELETE non-empty directory returns 409`() {
        project
        val key = workspaceProjectKey(project)

        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response = sessionClient.delete(sessionClient.rootPathUrl(key, json, "src"))
            Assertions.assertEquals(HttpStatusCode.Conflict, response.status)
        }
    }
}
