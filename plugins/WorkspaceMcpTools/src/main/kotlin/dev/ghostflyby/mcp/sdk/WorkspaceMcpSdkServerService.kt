/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * This file is part of IntelliJ-Plugins by ghostflyby
 *
 * IntelliJ-Plugins by ghostflyby is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see
 * <https://www.gnu.org/licenses/>.
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import dev.ghostflyby.mcp.resource.WorkspaceDocumentResourceReadOptions
import dev.ghostflyby.mcp.resource.WorkspaceListableResource
import dev.ghostflyby.mcp.resource.WorkspaceResourceCatalog
import dev.ghostflyby.mcp.resource.WorkspaceResourceException
import dev.ghostflyby.mcp.resource.WorkspaceResourceReader
import dev.ghostflyby.mcp.resource.WorkspaceVfsResourceReadOptions
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Service(Service.Level.APP)
internal class WorkspaceMcpSdkServerService(
    private val scope: CoroutineScope,
) : Disposable {
    private val logger = logger<WorkspaceMcpSdkServerService>()
    private val resourceReader = WorkspaceResourceReader()
    private val projectResolver = WorkspaceProjectResolver()
    private val resourceRegistryMutex = Mutex()
    private var listableResourceUris: Set<String> = emptySet()
    private val projectConnectionDisposables = linkedMapOf<Project, Disposable>()

    @Volatile
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @Volatile
    private var server: Server? = null

    init {
        subscribeToProjectEvents()
        scope.launch {
            runCatching {
                val listableResources = listWorkspaceResources()
                val createdServer = createServer(listableResources)
                val started = embeddedServer(CIO, host = LOOPBACK_HOST, port = 0) {
                    mcpStreamableHttp(path = MCP_ENDPOINT_PATH) {
                        createdServer
                    }
                }
                    .start(wait = false)
                server = createdServer
                engine = started
                listableResourceUris = listableResources.mapTo(mutableSetOf()) { it.uri }
                logger.info("Workspace MCP SDK server started at http://$LOOPBACK_HOST:<assigned>$MCP_ENDPOINT_PATH")
            }.onFailure { error ->
                logger.warn("Failed to start Workspace MCP SDK server", error)
            }
        }
    }

    override fun dispose() {
        scope.launch {
            closeServer()
        }
    }

    private suspend fun closeServer() {
        val stoppedEngine = engine
        val stoppedServer = server
        engine = null
        server = null
        runCatching {
            stoppedServer?.close()
        }.onFailure { error ->
            logger.warn("Failed to close Workspace MCP SDK server", error)
        }
        stoppedEngine?.stop()
    }

    internal fun refreshListableResources() {
        scope.launch {
            val activeServer = server ?: return@launch
            runCatching {
                refreshListableResources(activeServer, listWorkspaceResources())
            }.onFailure { error ->
                logger.warn("Failed to refresh Workspace MCP listable resources", error)
            }
        }
    }

    internal fun ensureProjectListeners(project: Project) {
        subscribeToProjectBus(project)
    }

    private suspend fun refreshListableResources(
        activeServer: Server,
        nextResources: List<WorkspaceListableResource>,
    ) {
        resourceRegistryMutex.withLock {
            val nextUris = nextResources.mapTo(mutableSetOf()) { it.uri }
            if (nextUris == listableResourceUris) {
                return
            }
            val removedUris = listableResourceUris - nextUris
            val addedResources = nextResources.filter { it.uri !in listableResourceUris }
            removedUris.forEach { uri ->
                activeServer.removeResource(uri)
            }
            activeServer.registerListableWorkspaceResources(addedResources)
            listableResourceUris = nextUris
        }
    }

    private suspend fun listWorkspaceResources(): List<WorkspaceListableResource> {
        return projectResolver.openProjects()
            .flatMap { project -> WorkspaceResourceCatalog(project).listResources() }
            .distinctBy { it.uri }
    }

    private fun createServer(listableResources: List<WorkspaceListableResource>): Server {
        return Server(
            serverInfo = Implementation(
                name = "workspace-mcp",
                version = "1.0.0",
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    resources = ServerCapabilities.Resources(
                        subscribe = true,
                        listChanged = true,
                    ),
                    tools = ServerCapabilities.Tools(
                        listChanged = true,
                    ),
                ),
                resourceTemplateMatcherFactory = WorkspaceResourceTemplateMatcherFactory,
            ),
            instructions = "Workspace MCP exposes IntelliJ VFS and editor document snapshots as MCP resources.",
        ) {
            registerListableWorkspaceResources(listableResources)
            registerWorkspaceResourceTemplates()
        }
    }

    private fun Server.registerListableWorkspaceResources(listableResources: List<WorkspaceListableResource>) {
        listableResources.forEach { entry ->
            addResource(
                uri = entry.uri,
                name = entry.name,
                description = entry.description,
                mimeType = entry.mimeType,
            ) {
                resourceReader.readWorkspaceResource(entry.uri).toReadResourceResult()
            }
        }
    }

    private fun Server.registerWorkspaceResourceTemplates() {
        addResourceTemplate(
            uriTemplate = "ij-workspace-vfs://{rawVfsUrl}",
            name = "IntelliJ VFS resource",
            description = "Reads IntelliJ VirtualFile content, directory listing, metadata, or API signature snapshots.",
            mimeType = "text/plain",
        ) { request, variables ->
            val rawVfsUrl = variables.requireRawVfsUrl()
            val content = resourceReader.readVfsResource(
                resourceUri = request.uri,
                rawVfsUrl = rawVfsUrl,
                options = WorkspaceVfsResourceReadOptions(),
            )
            content.toReadResourceResult()
        }

        addResourceTemplate(
            uriTemplate = "ij-workspace-document://{rawVfsUrl}",
            name = "IntelliJ editor document resource",
            description = "Reads the current IntelliJ editor document snapshot for a VFS URL, including unsaved text.",
            mimeType = "text/plain",
        ) { request, variables ->
            val rawVfsUrl = variables.requireRawVfsUrl()
            val content = resourceReader.readDocumentResource(
                resourceUri = request.uri,
                rawVfsUrl = rawVfsUrl,
                options = WorkspaceDocumentResourceReadOptions(),
            )
            content.toReadResourceResult()
        }
    }

    private fun Map<String, String>.requireRawVfsUrl(): String {
        return this["rawVfsUrl"]?.takeIf { it.isNotBlank() }
            ?: throw WorkspaceResourceException("Resource template did not provide rawVfsUrl.")
    }

    private fun dev.ghostflyby.mcp.resource.WorkspaceResourceTextContent.toReadResourceResult(): ReadResourceResult {
        return ReadResourceResult(
            contents = listOf(
                TextResourceContents(
                    uri = uri,
                    mimeType = mimeType,
                    text = text,
                ),
            ),
        )
    }

    private fun subscribeToProjectEvents() {
        projectResolver.openProjects().forEach { project ->
            subscribeToProjectBus(project)
        }

        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(
            ProjectManager.TOPIC,
            object : ProjectManagerListener {
                override fun projectClosed(project: Project) {
                    unsubscribeFromProjectBus(project)
                    refreshListableResources()
                }
            },
        )
    }

    private fun subscribeToProjectBus(project: Project) {
        if (project.isDisposed || project in projectConnectionDisposables) {
            return
        }

        val disposable = Disposer.newDisposable("Workspace MCP SDK project listeners: ${project.name}")
        Disposer.register(this, disposable)
        projectConnectionDisposables[project] = disposable

        val connection = project.messageBus.connect(disposable)
        connection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    refreshListableResources()
                }

                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    refreshListableResources()
                }
            },
        )
        connection.subscribe(
            ModuleRootListener.TOPIC,
            object : ModuleRootListener {
                override fun rootsChanged(event: ModuleRootEvent) {
                    refreshListableResources()
                }
            },
        )
    }

    private fun unsubscribeFromProjectBus(project: Project) {
        projectConnectionDisposables.remove(project)?.let { disposable ->
            Disposer.dispose(disposable)
        }
    }

    private companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val MCP_ENDPOINT_PATH = "/mcp"
    }
}
