/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.filecontent

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.*
import dev.ghostflyby.mcp.sdk.WorkspaceMcpStateFlows
import dev.ghostflyby.mcp.server.*
import dev.ghostflyby.mcp.server.route.WorkspaceResourceUriFormat
import dev.ghostflyby.mcp.server.route.resources.ProjectFileResource
import dev.ghostflyby.mcp.server.route.resources.ProjectResource
import dev.ghostflyby.mcp.server.route.resources.VfsResource
import io.modelcontextprotocol.kotlin.sdk.ExperimentalMcpApi
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.io.path.Path
import java.nio.file.Path as NioPath

@OptIn(ExperimentalMcpApi::class)
@TestApplication
internal class FileContentIntegrationTest {
    private val projectPathFixture = tempPathFixture()
    private val projectFixture = projectFixture(pathFixture = projectPathFixture, openAfterCreation = true)
    private val project by projectFixture
    private val moduleFixture = projectFixture.moduleFixture(name = "file-content")
    private val contentRootBlueprint = NioPath.of(
        requireNotNull(javaClass.getResource("/fileContentIntegration")) {
            "Missing fileContentIntegration test resources"
        }.toURI(),
    )
    private val contentRootFixture = moduleFixture.sourceRootFixture(
        pathFixture = projectFixture.pathInProjectFixture(Path(CONTENT_ROOT)),
        blueprintResourcePath = contentRootBlueprint,
    )

    private val openConnections = mutableListOf<McpConnection>()

    @BeforeEach
    fun refreshFixtureContent() {
        contentRootFixture.get().virtualFile.refresh(false, true)
    }

    @AfterEach
    fun closeMcpConnections() {
        runBlocking {
            openConnections.reversed().forEach { it.close() }
            openConnections.clear()
        }
    }

    @Test
    fun `reads raw VFS resource through real MCP client`() {
        runBlocking {
            val file = requireNotNull(contentRootFixture.get().virtualFile.findFileByRelativePath(SAMPLE_TEXT_FILE))
            val client = openMcpClient(ForbiddenProjectProvider)
            val uri = WorkspaceResourceUriFormat()
                .encodeToString(VfsResource.serializer(), VfsResource(rawVfsUrl = file.url))
                .withCurrentInstanceKey()

            val result = client.readTextResource(uri)

            assertEquals(SAMPLE_TEXT, result.text)
            assertEquals("text/plain", result.mimeType)
        }
    }

    @Test
    fun `reads project relative resource through real MCP client`() {
        runBlocking {
            val client = openMcpClient()
            val uri = WorkspaceResourceUriFormat()
                .encodeToString(
                    ProjectFileResource.serializer(),
                    ProjectFileResource(
                        parent = ProjectResource(workspaceProjectKey(project)),
                        relativePath = "$CONTENT_ROOT/$SAMPLE_TEXT_FILE",
                    ),
                )
                .withCurrentInstanceKey()

            val result = client.readTextResource(uri)

            assertEquals(SAMPLE_TEXT, result.text)
            assertEquals("text/plain", result.mimeType)
        }
    }

    @Test
    fun `reads selected metadata fields through real MCP client`() {
        runBlocking {
            val file = requireNotNull(contentRootFixture.get().virtualFile.findFileByRelativePath(SAMPLE_TEXT_FILE))
            val client = openMcpClient(ForbiddenProjectProvider)
            val uri = WorkspaceResourceUriFormat()
                .encodeToString(
                    VfsResource.serializer(),
                    VfsResource(rawVfsUrl = file.url, meta = "name,length,fileType"),
                )
                .withCurrentInstanceKey()

            val result = client.readTextResource(uri)
            val metadata = Json.parseToJsonElement(result.text).jsonObject

            assertEquals("application/json", result.mimeType)
            assertEquals("plain.txt", metadata["name"]?.jsonPrimitive?.content)
            assertEquals(SAMPLE_TEXT.length.toString(), metadata["length"]?.jsonPrimitive?.content)
            assertNotNull(metadata["fileType"], "fileType should be present")
            assertTrue("path" !in metadata, "metadata field filter should not include unrequested fields")
        }
    }

    @Test
    fun `reads raw VFS structure through real MCP client`() {
        runBlocking {
            val file =
                requireNotNull(contentRootFixture.get().virtualFile.findFileByRelativePath(SAMPLE_STRUCTURE_FILE))
            val client = openMcpClient()
            val uri = WorkspaceResourceUriFormat()
                .encodeToString(
                    VfsResource.serializer(),
                    VfsResource(rawVfsUrl = file.url),
                )
                .withCurrentInstanceKey()
                .withQueryFlag("structure")

            val result = client.readTextResource(uri)
            val structure = Json.parseToJsonElement(result.text).jsonObject

            assertEquals("application/json", result.mimeType)
            assertNotNull(structure["elements"], "structure response should include an elements array")
        }
    }

    @Test
    fun `raw VFS structure requires project resolution`() {
        runBlocking {
            val file =
                requireNotNull(contentRootFixture.get().virtualFile.findFileByRelativePath(SAMPLE_STRUCTURE_FILE))
            val client = openMcpClient(ForbiddenProjectProvider)
            val uri = WorkspaceResourceUriFormat()
                .encodeToString(
                    VfsResource.serializer(),
                    VfsResource(rawVfsUrl = file.url),
                )
                .withCurrentInstanceKey()
                .withQueryFlag("structure")

            val error = runCatching {
                client.readResource(ReadResourceRequest(ReadResourceRequestParams(uri = uri)))
            }.exceptionOrNull()

            assertInstanceOf(McpException::class.java, error)
        }
    }

    @Test
    fun `missing project relative resource reports MCP error`() {
        runBlocking {
            val client = openMcpClient()
            val uri = WorkspaceResourceUriFormat()
                .encodeToString(
                    ProjectFileResource.serializer(),
                    ProjectFileResource(
                        parent = ProjectResource(workspaceProjectKey(project)),
                        relativePath = "missing.txt",
                    ),
                )
                .withCurrentInstanceKey()

            val error = runCatching {
                client.readResource(ReadResourceRequest(ReadResourceRequestParams(uri = uri)))
            }.exceptionOrNull()

            assertInstanceOf(McpException::class.java, error)
        }
    }

    private suspend fun openMcpClient(
        projectProvider: WorkspaceProjectProvider = FixedProjectProvider(project),
    ): Client {
        val featureScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val core = WorkspaceMcpServerCore(
            parentScope = featureScope,
            projectResolver = projectProvider,
            serverInfo = Implementation(name = "workspace-mcp-test", version = "0.0.0"),
            instructions = "",
            initialFeatures = listOf(FileContentFeature()),
            stateFlows = WorkspaceMcpStateFlows(),
        )

        val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
        val client = Client(clientInfo = Implementation(name = "workspace-mcp-test-client", version = "0.0.0"))
        val connection = McpConnection(client = client, core = core, featureScope = featureScope)
        openConnections += connection

        coroutineScope {
            listOf(
                launch { client.connect(clientTransport) },
                launch { core.server.createSession(serverTransport) },
            ).joinAll()
        }

        return client
    }

    private suspend fun Client.readTextResource(uri: String): TextResourceContents {
        val result = readResource(ReadResourceRequest(ReadResourceRequestParams(uri = uri)))
        assertEquals(1, result.contents.size)
        return result.contents.single() as TextResourceContents
    }

    private fun String.withCurrentInstanceKey(): String =
        replace("{instanceKey}", workspaceInstanceKey())

    private fun String.withQueryFlag(name: String): String =
        if ('?' in this) "$this&$name" else "$this?$name"

    private data class McpConnection(
        val client: Client,
        val core: WorkspaceMcpServerCore,
        val featureScope: CoroutineScope,
    ) {
        suspend fun close() {
            runCatching { client.close() }
            runCatching { core.close() }
            featureScope.cancel()
        }
    }

    private class FixedProjectProvider(
        private val project: Project,
    ) : WorkspaceProjectProvider {
        override fun openProjects(): List<Project> = listOf(project).filterNot { it.isDisposed }

        override suspend fun resolve(
            projectKey: String?,
            projectPath: String?,
            rawVfsUrl: String?,
            relativePath: String?,
            rootsCandidates: List<String>?,
        ): WorkspaceProjectResolution {
            val expectedKey = workspaceProjectKey(project)
            return if (projectKey == null || projectKey == expectedKey) {
                WorkspaceProjectResolution.Resolved(project, WorkspaceProjectResolutionReason.EXPLICIT_PROJECT_KEY)
            } else {
                WorkspaceProjectResolution.Unresolved("Unknown test project key: $projectKey")
            }
        }
    }

    private object ForbiddenProjectProvider : WorkspaceProjectProvider {
        override fun openProjects(): List<Project> = error("Project provider should not be queried.")

        override suspend fun resolve(
            projectKey: String?,
            projectPath: String?,
            rawVfsUrl: String?,
            relativePath: String?,
            rootsCandidates: List<String>?,
        ): WorkspaceProjectResolution = error("Project provider should not be queried.")
    }

    @Test
    fun `raw VFS glob expands files matching pattern`() {
        runBlocking {
            val dir = contentRootFixture.get().virtualFile
            val client = openMcpClient(ForbiddenProjectProvider)
            val uri = WorkspaceResourceUriFormat()
                .encodeToString(
                    VfsResource.serializer(),
                    VfsResource(rawVfsUrl = dir.url, glob = "**/*.xml"),
                )
                .withCurrentInstanceKey()

            val result = client.readTextResource(uri)
            val paths = Json.parseToJsonElement(result.text).jsonArray

            assertEquals("application/json", result.mimeType)
            assertEquals(SAMPLE_XML_FILES, paths.map { it.jsonPrimitive.content }.sorted())
        }
    }

    @Test
    fun `raw VFS glob on non-directory throws`() {
        runBlocking {
            val file = requireNotNull(contentRootFixture.get().virtualFile.findFileByRelativePath(SAMPLE_TEXT_FILE))
            val client = openMcpClient(ForbiddenProjectProvider)
            val uri = WorkspaceResourceUriFormat()
                .encodeToString(
                    VfsResource.serializer(),
                    VfsResource(rawVfsUrl = file.url, glob = "*"),
                )
                .withCurrentInstanceKey()

            val error = runCatching {
                client.readResource(ReadResourceRequest(ReadResourceRequestParams(uri = uri)))
            }.exceptionOrNull()

            assertInstanceOf(McpException::class.java, error)
        }
    }

    @Test
    fun `raw VFS glob with no matches returns empty array`() {
        runBlocking {
            val dir = contentRootFixture.get().virtualFile
            val client = openMcpClient(ForbiddenProjectProvider)
            val uri = WorkspaceResourceUriFormat()
                .encodeToString(
                    VfsResource.serializer(),
                    VfsResource(rawVfsUrl = dir.url, glob = "*.rs"),
                )
                .withCurrentInstanceKey()

            val result = client.readTextResource(uri)

            assertEquals("application/json", result.mimeType)
            assertEquals("[]", result.text)
        }
    }

    @Test
    fun `project relative extension glob uses FileTypeIndex fast path`() {
        runBlocking {
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            val client = openMcpClient()
            val uri = WorkspaceResourceUriFormat()
                .encodeToString(
                    ProjectFileResource.serializer(),
                    ProjectFileResource(
                        parent = ProjectResource(workspaceProjectKey(project)),
                        relativePath = "$CONTENT_ROOT/src",
                        glob = "*.xml",
                    ),
                )
                .withCurrentInstanceKey()

            val result = client.readTextResource(uri)
            val paths = Json.parseToJsonElement(result.text).jsonArray

            assertEquals("application/json", result.mimeType)
            assertEquals(listOf("bar.xml", "foo.xml"), paths.map { it.jsonPrimitive.content }.sorted())
        }
    }

    @Test
    fun `project relative glob recursive extension uses FileTypeIndex fast path`() {
        runBlocking {
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            val client = openMcpClient()
            val uri = WorkspaceResourceUriFormat()
                .encodeToString(
                    ProjectFileResource.serializer(),
                    ProjectFileResource(
                        parent = ProjectResource(workspaceProjectKey(project)),
                        relativePath = CONTENT_ROOT,
                        glob = "**/*.xml",
                    ),
                )
                .withCurrentInstanceKey()

            val result = client.readTextResource(uri)
            val paths = Json.parseToJsonElement(result.text).jsonArray

            assertEquals("application/json", result.mimeType)
            assertEquals(SAMPLE_XML_FILES, paths.map { it.jsonPrimitive.content }.sorted())
        }
    }

    @Test
    fun `project relative glob with literal directory prefix uses FileTypeIndex fast path`() {
        runBlocking {
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            val client = openMcpClient()
            val uri = WorkspaceResourceUriFormat()
                .encodeToString(
                    ProjectFileResource.serializer(),
                    ProjectFileResource(
                        parent = ProjectResource(workspaceProjectKey(project)),
                        relativePath = CONTENT_ROOT,
                        glob = "src/*.xml",
                    ),
                )
                .withCurrentInstanceKey()

            val result = client.readTextResource(uri)
            val paths = Json.parseToJsonElement(result.text).jsonArray

            assertEquals("application/json", result.mimeType)
            assertEquals(
                listOf("src/bar.xml", "src/foo.xml"),
                paths.map { it.jsonPrimitive.content }.sorted(),
            )
        }
    }

    @Test
    fun `project relative glob with intermediate wildcard directory uses FileTypeIndex fast path`() {
        runBlocking {
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            val client = openMcpClient()
            val uri = WorkspaceResourceUriFormat()
                .encodeToString(
                    ProjectFileResource.serializer(),
                    ProjectFileResource(
                        parent = ProjectResource(workspaceProjectKey(project)),
                        relativePath = CONTENT_ROOT,
                        glob = "**/any/*/target/*.xml",
                    ),
                )
                .withCurrentInstanceKey()

            val result = client.readTextResource(uri)
            val paths = Json.parseToJsonElement(result.text).jsonArray

            assertEquals("application/json", result.mimeType)
            assertEquals(
                listOf("any/bar/target/b.xml", "any/foo/target/a.xml"),
                paths.map { it.jsonPrimitive.content }.sorted(),
            )
        }
    }

    @Test
    fun `extension glob patterns use FileTypeIndex lookup before glob filtering`() {
        runBlocking {
            val root = contentRootFixture.get().virtualFile
            val lookup = RecordingFileTypeIndexLookup(root)

            assertGlobViaIndex(root, "*.xml", lookup, listOf("root.xml"))
            assertGlobViaIndex(root, "**/*.xml", lookup, SAMPLE_XML_FILES)
            assertGlobViaIndex(root, "src/*.xml", lookup, listOf("src/bar.xml", "src/foo.xml"))
            assertGlobViaIndex(root, "nested/deep/*.xml", lookup, listOf("nested/deep/c.xml"))
            assertGlobViaIndex(root, "any/foo/target/*.xml", lookup, listOf("any/foo/target/a.xml"))
            assertGlobViaIndex(
                root,
                "**/any/*/target/*.xml",
                lookup,
                listOf("any/bar/target/b.xml", "any/foo/target/a.xml"),
            )

            assertEquals(listOf("xml", "xml", "xml", "xml", "xml", "xml"), lookup.fileTypeNames.map { it.lowercase() })
        }
    }

    @Test
    fun `non extension glob patterns bypass FileTypeIndex lookup`() {
        runBlocking {
            val root = contentRootFixture.get().virtualFile
            val lookup = RecordingFileTypeIndexLookup(root)
            val result = readGlobResult(root, "src/foo.*", project, lookup)
            val paths = Json.parseToJsonElement(result.payload).jsonArray

            assertEquals("application/json", result.mimeType)
            assertEquals(listOf("src/foo.xml"), paths.map { it.jsonPrimitive.content }.sorted())
            assertEquals(emptyList<String>(), lookup.fileTypeNames)
        }
    }

    private suspend fun assertGlobViaIndex(
        root: VirtualFile,
        pattern: String,
        lookup: RecordingFileTypeIndexLookup,
        expectedPaths: List<String>,
    ) {
        val callCountBefore = lookup.fileTypeNames.size
        val result = readGlobResult(root, pattern, project, lookup)
        val paths = Json.parseToJsonElement(result.payload).jsonArray

        assertEquals("application/json", result.mimeType)
        assertEquals(expectedPaths, paths.map { it.jsonPrimitive.content }.sorted())
        assertEquals(callCountBefore + 1, lookup.fileTypeNames.size, "FileTypeIndex lookup should be used for $pattern")
    }

    private class RecordingFileTypeIndexLookup(
        private val root: VirtualFile,
    ) : FileTypeIndexLookup {
        val fileTypeNames = mutableListOf<String>()

        override fun getFiles(fileType: FileType, scope: GlobalSearchScope): Collection<VirtualFile> {
            fileTypeNames += fileType.name
            val files = mutableListOf<VirtualFile>()
            VfsUtilCore.iterateChildrenRecursively(root, null) { file ->
                if (!file.isDirectory && file.fileType == fileType && scope.contains(file)) {
                    files += file
                }
                true
            }
            return files
        }
    }

    private companion object {
        const val CONTENT_ROOT = "fileContent"
        const val SAMPLE_TEXT = "hello sample\n"
        const val SAMPLE_TEXT_FILE = "plain.txt"
        const val SAMPLE_STRUCTURE_FILE = "src/foo.xml"
        val SAMPLE_XML_FILES = listOf(
            "any/bar/target/b.xml",
            "any/foo/target/a.xml",
            "nested/deep/c.xml",
            "root.xml",
            "src/bar.xml",
            "src/foo.xml",
            "unmatched/ignored.xml",
        )
    }
}
