package dev.ghostflyby.mcp.rest

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.resources.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

@TestApplication
internal class FileSearchRoutesTest {

    private val projectPathFixture = tempPathFixture()
    private val projectFixture = projectFixture(pathFixture = projectPathFixture, openAfterCreation = true)
    private val project by projectFixture
    private val moduleFixture = projectFixture.moduleFixture(name = "file-search-test")
    private val contentRootFixture = moduleFixture.sourceRootFixture(
        pathFixture = projectFixture.pathInProjectFixture(Path.of("")),
    )
    private val json = Json { ignoreUnknownKeys = true }
    private var contributorDisposable: Disposable? = null

    @BeforeEach
    fun setupFiles() {
        project
        val root = Path.of(requireNotNull(project.basePath))
        Files.createDirectories(root.resolve("src/in/AlphaDirectory"))
        Files.createDirectories(root.resolve("src/out"))
        root.resolve("src/in/AlphaFile.kt").writeText("class AlphaFile")
        root.resolve("src/in/BetaFile.kt").writeText("class BetaFile")
        root.resolve("src/out/AlphaOutside.kt").writeText("class AlphaOutside")
        contentRootFixture.get().virtualFile.refresh(false, true)
        registerFixtureFileContributor(root)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    @AfterEach
    fun tearDown() {
        contributorDisposable?.let(Disposer::dispose)
        contributorDisposable = null
    }

    @Test
    fun `file search requires session header`() {
        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val missing = client.get(searchFilesUrl(query = "AlphaFile")) {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.NotFound, missing.status)

            val invalid = client.get(searchFilesUrl(query = "AlphaFile")) {
                header(RestSessionHeader, "missing-session")
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.NotFound, invalid.status)
        }
    }

    @Test
    fun `file search rejects blank query`() {
        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().resolve("src/in").toString(), json)

            val response = sessionClient.get(searchFilesUrl(query = "   ")) {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `file search uses fuzzy goto file matching within session prefix`() {
        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().resolve("src/in").toString(), json)

            val response = sessionClient.get(searchFilesUrl(query = "AF")) {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val items = items(response.bodyAsText())
            Assertions.assertTrue(
                items.any { it.jsonObject["name"]!!.jsonPrimitive.content == "AlphaFile.kt" },
                response.bodyAsText(),
            )
            Assertions.assertTrue(
                items.none { it.jsonObject["name"]!!.jsonPrimitive.content == "AlphaOutside.kt" },
                response.bodyAsText(),
            )
            Assertions.assertTrue(
                items.none { it.jsonObject["name"]!!.jsonPrimitive.content == "AlphaDirectory" },
                response.bodyAsText(),
            )
            val alpha = items.first { it.jsonObject["name"]!!.jsonPrimitive.content == "AlphaFile.kt" }.jsonObject
            Assertions.assertTrue(alpha["filePath"]!!.jsonPrimitive.content.endsWith("src/in/AlphaFile.kt"))
            Assertions.assertEquals("AlphaFile.kt", alpha["relativePath"]!!.jsonPrimitive.content)
            Assertions.assertEquals(1, alpha["line"]!!.jsonPrimitive.int)
            Assertions.assertEquals(1, alpha["column"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun `file search marks truncated results`() {
        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().resolve("src/in").toString(), json)

            val response = sessionClient.get(searchFilesUrl(query = "File", limit = 1)) {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            Assertions.assertEquals(1, body["items"]!!.jsonArray.size)
            Assertions.assertTrue(body["truncated"]!!.jsonPrimitive.boolean)
        }
    }

    @Test
    fun `file search defaults to markdown table`() {
        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().resolve("src/in").toString(), json)

            val response = sessionClient.get(searchFilesUrl(query = "AlphaFile"))
            Assertions.assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            Assertions.assertEquals(TestMarkdownContentType, response.responseContentType())
            val body = response.bodyAsText()
            Assertions.assertTrue(body.contains("## Files"), body)
            Assertions.assertTrue(body.contains("| name | path | fileType | score |"), body)
            Assertions.assertTrue(body.contains("AlphaFile.kt"), body)
        }
    }

    private fun items(body: String): JsonArray {
        return json.parseToJsonElement(body).jsonObject["items"]!!.jsonArray
    }

    private fun registerFixtureFileContributor(root: Path) {
        contributorDisposable?.let(Disposer::dispose)
        val disposable = Disposer.newDisposable("FileSearchRoutesTest.fixtureFileContributor")
        contributorDisposable = disposable
        ChooseByNameContributor.FILE_EP_NAME.point.registerExtension(
            FixtureFileContributor(
                root = root,
                entries = mapOf(
                    "AlphaFile.kt" to "src/in/AlphaFile.kt",
                    "BetaFile.kt" to "src/in/BetaFile.kt",
                    "AlphaOutside.kt" to "src/out/AlphaOutside.kt",
                    "AlphaDirectory" to "src/in/AlphaDirectory",
                ),
            ),
            disposable,
        )
    }

    private class FixtureFileContributor(
        private val root: Path,
        private val entries: Map<String, String>,
    ) : ChooseByNameContributor {
        override fun getNames(project: Project, includeNonProjectItems: Boolean): Array<String> {
            return entries.keys.toTypedArray()
        }

        override fun getItemsByName(
            name: String,
            pattern: String,
            project: Project,
            includeNonProjectItems: Boolean,
        ): Array<NavigationItem> {
            val relativePath = entries[name] ?: return NavigationItem.EMPTY_NAVIGATION_ITEM_ARRAY
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve(relativePath))
                ?: return NavigationItem.EMPTY_NAVIGATION_ITEM_ARRAY
            val psi = if (file.isDirectory) {
                PsiManager.getInstance(project).findDirectory(file)
            } else {
                PsiManager.getInstance(project).findFile(file)
            } ?: return NavigationItem.EMPTY_NAVIGATION_ITEM_ARRAY
            return arrayOf(psi as NavigationItem)
        }
    }
}