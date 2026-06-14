package dev.ghostflyby.mcp.rest

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.navigation.PsiElementNavigationItem
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
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
internal class SymbolSearchRoutesTest {

    private val projectPathFixture = tempPathFixture()
    private val projectFixture = projectFixture(pathFixture = projectPathFixture, openAfterCreation = true)
    private val project by projectFixture
    private val moduleFixture = projectFixture.moduleFixture(name = "symbol-search-test")
    private val contentRootFixture = moduleFixture.sourceRootFixture(
        pathFixture = projectFixture.pathInProjectFixture(Path.of("")),
    )
    private val json = Json { ignoreUnknownKeys = true }
    private var contributorDisposable: Disposable? = null

    @BeforeEach
    fun setupFiles() {
        project
        val root = Path.of(requireNotNull(project.basePath))
        val srcDir = Files.createDirectories(root.resolve("src/sample"))
        srcDir.resolve("AlphaSymbol.kt").writeText(
            """
            package sample

            class AlphaSymbol {
                val fieldSymbol: Int = 1

                fun methodSymbol(): Int = fieldSymbol
            }

            class BetaSymbol

            object GammaSymbol
            """.trimIndent(),
        )
        contentRootFixture.get().virtualFile.refresh(false, true)
        registerFixtureSymbolContributor(root)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    @AfterEach
    fun tearDown() {
        contributorDisposable?.let(Disposer::dispose)
        contributorDisposable = null
    }

    @Test
    fun `symbol search requires session header`() {
        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }

            val missing = client.get(searchSymbolsUrl(query = "AlphaSymbol")) {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.NotFound, missing.status)

            val invalid = client.get(searchSymbolsUrl(query = "AlphaSymbol")) {
                header(RestSessionHeader, "missing-session")
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.NotFound, invalid.status)
        }
    }

    @Test
    fun `symbol search rejects blank query`() {
        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response = sessionClient.get(searchSymbolsUrl(query = "   ")) {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `default project search finds symbol locations`() {
        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val item = firstItem(
                sessionClient.get(searchSymbolsUrl(query = "AlphaSymbol")) {
                    accept(ContentType.Application.Json)
                },
            )
            Assertions.assertEquals("AlphaSymbol", item["name"]!!.jsonPrimitive.content)
            Assertions.assertEquals("symbol", item["kind"]!!.jsonPrimitive.content)
            Assertions.assertTrue(item["filePath"]!!.jsonPrimitive.content.endsWith("src/sample/AlphaSymbol.kt"))
            Assertions.assertTrue(item["line"]!!.jsonPrimitive.int >= 1)
            Assertions.assertTrue(item["column"]!!.jsonPrimitive.int >= 1)
        }
    }

    @Test
    fun `kind filter supports generic symbol kind`() {
        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response = sessionClient.get(searchSymbolsUrl(query = "AlphaSymbol", kind = "symbol")) {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            val items = items(response.bodyAsText())
            Assertions.assertTrue(items.any { it.jsonObject["name"]!!.jsonPrimitive.content == "AlphaSymbol" })
            Assertions.assertTrue(items.all { it.jsonObject["kind"]!!.jsonPrimitive.content == "symbol" })
        }
    }

    @Test
    fun `limit marks truncated results`() {
        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response = sessionClient.get(searchSymbolsUrl(query = "Symbol", limit = 1)) {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            Assertions.assertEquals(1, body["items"]!!.jsonArray.size)
            Assertions.assertTrue(body["truncated"]!!.jsonPrimitive.boolean)
        }
    }

    @Test
    fun `project only search does not return library locations`() {
        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response = sessionClient.get(searchSymbolsUrl(query = "String", libraries = false, limit = 20)) {
                accept(ContentType.Application.Json)
            }
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            val items = items(response.bodyAsText())
            Assertions.assertTrue(
                items.none { item ->
                    val fileUrl = item.jsonObject["fileUrl"]!!.jsonPrimitive.content
                    fileUrl.startsWith("jar://") || fileUrl.contains("!/")
                },
            )
        }
    }

    @Test
    fun `symbol search defaults to markdown table`() {
        testApplication {
            application { installWorkspaceRestContentNegotiation() }
            install(Resources)
            routing { restApi() }
            val sessionClient = client.withRestSession(projectPathFixture.get().toString(), json)

            val response = sessionClient.get(searchSymbolsUrl(query = "AlphaSymbol"))
            Assertions.assertEquals(HttpStatusCode.OK, response.status)
            Assertions.assertEquals(TestMarkdownContentType, response.responseContentType())
            val body = response.bodyAsText()
            Assertions.assertTrue(body.contains("## Symbols"), body)
            Assertions.assertTrue(body.contains("| name | kind | path | line | qualifiedName |"), body)
            Assertions.assertTrue(body.contains("AlphaSymbol"), body)
        }
    }

    private suspend fun firstItem(response: HttpResponse): JsonObject {
        Assertions.assertEquals(HttpStatusCode.OK, response.status)
        val items = items(response.bodyAsText())
        Assertions.assertTrue(items.isNotEmpty(), "expected at least 1 symbol")
        return items[0].jsonObject
    }

    private fun items(body: String): JsonArray {
        return json.parseToJsonElement(body).jsonObject["items"]!!.jsonArray
    }

    private fun registerFixtureSymbolContributor(root: Path) {
        contributorDisposable?.let(Disposer::dispose)
        val disposable = Disposer.newDisposable("SymbolSearchRoutesTest.fixtureSymbolContributor")
        contributorDisposable = disposable
        ChooseByNameContributor.SYMBOL_EP_NAME.point.registerExtension(
            FixtureSymbolContributor(
                root = root,
                symbols = mapOf(
                    "AlphaSymbol" to "src/sample/AlphaSymbol.kt",
                    "BetaSymbol" to "src/sample/AlphaSymbol.kt",
                    "GammaSymbol" to "src/sample/AlphaSymbol.kt",
                    "fieldSymbol" to "src/sample/AlphaSymbol.kt",
                    "methodSymbol" to "src/sample/AlphaSymbol.kt",
                ),
            ),
            disposable,
        )
    }

    private class FixtureSymbolContributor(
        private val root: Path,
        private val symbols: Map<String, String>,
    ) : ChooseByNameContributor {
        override fun getNames(project: Project, includeNonProjectItems: Boolean): Array<String> {
            return symbols.keys.toTypedArray()
        }

        override fun getItemsByName(
            name: String,
            pattern: String,
            project: Project,
            includeNonProjectItems: Boolean,
        ): Array<NavigationItem> {
            val relativePath = symbols[name] ?: return NavigationItem.EMPTY_NAVIGATION_ITEM_ARRAY
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve(relativePath))
                ?: return NavigationItem.EMPTY_NAVIGATION_ITEM_ARRAY
            val psiFile = PsiManager.getInstance(project).findFile(file)
                ?: return NavigationItem.EMPTY_NAVIGATION_ITEM_ARRAY
            return arrayOf(FixtureSymbolItem(name, psiFile))
        }
    }

    private class FixtureSymbolItem(
        private val symbolName: String,
        private val target: PsiElement,
    ) : PsiElementNavigationItem {
        override fun getTargetElement(): PsiElement = target
        override fun getName(): String = symbolName
        override fun getPresentation(): ItemPresentation? = null

        override fun navigate(requestFocus: Boolean) {
            val file = target.containingFile?.virtualFile ?: return
            OpenFileDescriptor(target.project, file, target.textOffset).navigate(requestFocus)
        }

        override fun canNavigate(): Boolean = target.containingFile?.virtualFile != null
        override fun canNavigateToSource(): Boolean = canNavigate()
    }
}