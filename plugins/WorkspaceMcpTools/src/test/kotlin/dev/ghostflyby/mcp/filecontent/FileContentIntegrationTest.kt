/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.filecontent

import com.intellij.openapi.application.backgroundWriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.refreshAndFindVirtualFileOrDirectory
import com.intellij.project.stateStore
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import dev.ghostflyby.mcp.route.ResourceRouteSnapshotRef
import dev.ghostflyby.mcp.route.SegmentTreeTemplateMatcher
import dev.ghostflyby.mcp.route.WorkspaceResourceUriFormat
import dev.ghostflyby.mcp.route.resources.ProjectFileResource
import dev.ghostflyby.mcp.route.resources.ProjectResource
import dev.ghostflyby.mcp.route.resources.VfsResource
import dev.ghostflyby.mcp.sdk.*
import io.modelcontextprotocol.kotlin.sdk.ExperimentalMcpApi
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.*
import io.modelcontextprotocol.kotlin.sdk.utils.ResourceTemplateMatcherFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.io.path.createDirectories

@OptIn(ExperimentalMcpApi::class)
@TestApplication
internal class FileContentIntegrationTest {
    private val project by projectFixture(openAfterCreation = true)

    private val openConnections = mutableListOf<McpConnection>()

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
            val file = createProjectFile("test.txt", "hello raw")
            val client = openMcpClient(ForbiddenProjectProvider)
            val uri = WorkspaceResourceUriFormat()
                .encodeToString(VfsResource.serializer(), VfsResource(rawVfsUrl = file.url))
                .withCurrentInstanceKey()

            val result = client.readTextResource(uri)

            assertEquals("hello raw", result.text)
            assertEquals("text/plain", result.mimeType)
        }
    }

    @Test
    fun `reads project relative resource through real MCP client`() {
        runBlocking {
            createProjectFile("test.txt", "hello project")
            val client = openMcpClient()
            val uri = WorkspaceResourceUriFormat()
                .encodeToString(
                    ProjectFileResource.serializer(),
                    ProjectFileResource(
                        parent = ProjectResource(workspaceProjectKey(project)),
                        relativePath = "test.txt",
                    ),
                )
                .withCurrentInstanceKey()

            val result = client.readTextResource(uri)

            assertEquals("hello project", result.text)
            assertEquals("text/plain", result.mimeType)
        }
    }

    @Test
    fun `reads selected metadata fields through real MCP client`() {
        runBlocking {
            val file = createProjectFile("test.txt", "hello metadata")
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
            assertEquals("test.txt", metadata["name"]?.jsonPrimitive?.content)
            assertEquals("14", metadata["length"]?.jsonPrimitive?.content)
            assertNotNull(metadata["fileType"], "fileType should be present")
            assertTrue("path" !in metadata, "metadata field filter should not include unrequested fields")
        }
    }

    @Test
    fun `reads raw VFS structure through real MCP client`() {
        runBlocking {
            val file = createProjectFile("Test.kt", "class Test { fun answer() = 42 }")
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
            val file = createProjectFile("Test.kt", "class Test")
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

    private suspend fun createProjectFile(relativePath: String, text: String): VirtualFile {
        val pathSegments = relativePath.split('/')
        val projectRoot = project.stateStore.projectBasePath
        return backgroundWriteAction {
            projectRoot.createDirectories()
            var directory = projectRoot.refreshAndFindVirtualFileOrDirectory()
                ?: error("Project root was not found in VFS: $projectRoot")
            pathSegments.dropLast(1).forEach { segment ->
                directory = directory.findChild(segment) ?: directory.createChildDirectory(this, segment)
            }
            directory.createChildData(this, pathSegments.last()).also { file ->
                file.setBinaryContent(text.toByteArray())
            }
        }
    }

    private suspend fun openMcpClient(
        projectProvider: WorkspaceProjectProvider = FixedProjectProvider(project),
    ): Client {
        val routeSnapshotRef = ResourceRouteSnapshotRef()
        val catalog = WorkspaceMcpResourceCatalog()
        val featureScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val server = Server(
            serverInfo = Implementation(name = "workspace-mcp-test", version = "0.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    resources = ServerCapabilities.Resources(listChanged = true),
                    tools = ServerCapabilities.Tools(),
                ),
                resourceTemplateMatcherFactory = ResourceTemplateMatcherFactory { template ->
                    SegmentTreeTemplateMatcher(template, routeSnapshotRef)
                },
            ),
        ) {
            WorkspaceMcpFeatureCoordinator(
                parentScope = featureScope,
                projectResolver = projectProvider,
                catalog = catalog,
                onSnapshotChanged = routeSnapshotRef::set,
                invalidationSink = NoopInvalidationSink,
            ).registerInitial(this, listOf(FileContentFeature()))
        }
        server.onConnect { session ->
            session.setRequestHandler<ListResourcesRequest>(Method.Defined.ResourcesList) { request, _ ->
                catalog.listResources(server.clientConnection(session.sessionId), request)
            }
            session.setRequestHandler<ListResourceTemplatesRequest>(Method.Defined.ResourcesTemplatesList) { request, _ ->
                catalog.listTemplates(server.clientConnection(session.sessionId), request)
            }
        }

        val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
        val client = Client(clientInfo = Implementation(name = "workspace-mcp-test-client", version = "0.0.0"))
        val connection = McpConnection(client = client, server = server, featureScope = featureScope)
        openConnections += connection

        coroutineScope {
            listOf(
                launch { client.connect(clientTransport) },
                launch { server.createSession(serverTransport) },
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
        val server: Server,
        val featureScope: CoroutineScope,
    ) {
        suspend fun close() {
            runCatching { client.close() }
            runCatching { server.close() }
            featureScope.cancel()
        }
    }

    private object NoopInvalidationSink : WorkspaceMcpInvalidationSink {
        override fun <T> registerResourceUpdates(flow: StateFlow<T>, uris: (T) -> Set<String>) = Unit

        override fun <T> registerGlobalListChanged(kind: ListChangeKind, flow: StateFlow<T>) = Unit

        override fun <T> registerSessionListChanged(
            kind: ListChangeKind,
            flow: StateFlow<T>,
            sessions: (T) -> Set<String>,
        ) = Unit
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
}
