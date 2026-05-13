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
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import dev.ghostflyby.mcp.resource.WorkspaceListableResource
import dev.ghostflyby.mcp.resource.WorkspaceResourceCatalog
import dev.ghostflyby.mcp.resource.WorkspaceResourceReader
import dev.ghostflyby.mcp.resource.tryDecodeWorkspaceResourceUri
import dev.ghostflyby.mcp.resource.workspaceVfsUri
import dev.ghostflyby.mcp.resource.workspaceDocumentUri
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.EmptyResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.ResourceUpdatedNotification
import io.modelcontextprotocol.kotlin.sdk.types.ResourceUpdatedNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.SubscribeRequest
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.UnsubscribeRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds

@Service(Service.Level.APP)
internal class WorkspaceMcpSdkServerService(
    private val scope: CoroutineScope,
) : Disposable {
    private val logger = logger<WorkspaceMcpSdkServerService>()
    private val projectResolver = WorkspaceProjectResolver()
    private val resourceReader = WorkspaceResourceReader(projectResolver)
    private val resourceRegistryMutex = Mutex()
    private var listableResourceUris: Set<String> = emptySet()
    private val projectConnectionDisposables = linkedMapOf<Project, Disposable>()
    private val resourceUpdateStateLock = Any()
    private val resourceSubscriptionsBySession = linkedMapOf<String, MutableSet<String>>()
    private val subscriptionHandlerSessionIds = linkedSetOf<String>()
    private val pendingResourceUpdateUris = linkedSetOf<String>()
    private var resourceUpdateFlushJob: Job? = null

    @Volatile
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @Volatile
    private var server: Server? = null

    init {
        subscribeToProjectEvents()
        subscribeToResourceUpdateEvents()
        scope.launch {
            runCatching {
                val listableResources = listWorkspaceResources()
                val createdServer = createServer(listableResources)
                val port = service<WorkspaceMcpSdkServerSettings>().port
                val started = embeddedServer(CIO, host = LOOPBACK_HOST, port = port) {
                    mcpStreamableHttp(path = MCP_ENDPOINT_PATH) {
                        createdServer
                    }
                }
                    .start(wait = false)
                server = createdServer
                engine = started
                listableResourceUris = listableResources.mapTo(mutableSetOf()) { it.uri }
                logger.info("Workspace MCP SDK server started at http://$LOOPBACK_HOST:$port$MCP_ENDPOINT_PATH")
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
            onConnect {
                installWorkspaceSubscriptionHandlers()
            }
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
        // New scheme templates (project-scoped)
        addResourceTemplate(
            uriTemplate = NEW_WORKSPACE_FILES_TEMPLATE,
            name = "Project file resource",
            description = "Reads IntelliJ VirtualFile content by project-relative path.",
            mimeType = "text/plain",
        ) { request, _ ->
            val content = resourceReader.readWorkspaceResource(request.uri)
            content.toReadResourceResult()
        }

        addResourceTemplate(
            uriTemplate = NEW_WORKSPACE_DOCUMENTS_TEMPLATE,
            name = "Project document resource",
            description = "Reads the current editor document snapshot by project-relative path, including unsaved text.",
            mimeType = "text/plain",
        ) { request, _ ->
            val content = resourceReader.readWorkspaceResource(request.uri)
            content.toReadResourceResult()
        }

        addResourceTemplate(
            uriTemplate = NEW_WORKSPACE_VFS_TEMPLATE,
            name = "Project VFS resource",
            description = "Reads IntelliJ VirtualFile content by raw VFS URL within a project scope.",
            mimeType = "text/plain",
        ) { request, _ ->
            val content = resourceReader.readWorkspaceResource(request.uri)
            content.toReadResourceResult()
        }

        addResourceTemplate(
            uriTemplate = NEW_WORKSPACE_DOCUMENT_VFS_TEMPLATE,
            name = "Project document VFS resource",
            description = "Reads the current editor document snapshot by raw VFS URL within a project scope.",
            mimeType = "text/plain",
        ) { request, _ ->
            val content = resourceReader.readWorkspaceResource(request.uri)
            content.toReadResourceResult()
        }
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

    private fun subscribeToResourceUpdateEvents() {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    scheduleDocumentResourceUpdate(event.document)
                }
            },
            this,
        )

        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    events.mapNotNull { event -> event.file?.url }
                        .flatMap { url ->
                            projectResolver.openProjects().mapNotNull { project ->
                                val bp = project.basePath
                                if (bp != null && url.startsWith("file://$bp")) {
                                    workspaceVfsUri(workspaceInstanceKey(), workspaceProjectKey(project), url)
                                } else if (url.startsWith("file://") && projectResolver.openProjects().size == 1) {
                                    workspaceVfsUri(workspaceInstanceKey(), workspaceProjectKey(project), url)
                                } else {
                                    null
                                }
                            }
                        }
                        .distinct()
                        .forEach(::scheduleResourceUpdated)
                    refreshListableResources()
                }
            },
        )
    }

    private fun Server.installWorkspaceSubscriptionHandlers() {
        sessions.values.forEach { session ->
            installWorkspaceSubscriptionHandlers(session)
        }
    }

    private fun installWorkspaceSubscriptionHandlers(session: ServerSession) {
        val shouldInstall = synchronized(resourceUpdateStateLock) {
            subscriptionHandlerSessionIds.add(session.sessionId)
        }
        if (!shouldInstall) {
            return
        }

        session.setRequestHandler<SubscribeRequest>(Method.Defined.ResourcesSubscribe) { request, _ ->
            recordResourceSubscription(session.sessionId, request.params.uri)
            EmptyResult()
        }
        session.setRequestHandler<UnsubscribeRequest>(Method.Defined.ResourcesUnsubscribe) { request, _ ->
            removeResourceSubscription(session.sessionId, request.params.uri)
            EmptyResult()
        }
    }

    private fun recordResourceSubscription(sessionId: String, resourceUri: String) {
        if (tryDecodeWorkspaceResourceUri(resourceUri) == null) {
            return
        }
        synchronized(resourceUpdateStateLock) {
            resourceSubscriptionsBySession.getOrPut(sessionId) { linkedSetOf() }.add(resourceUri)
        }
    }

    private fun removeResourceSubscription(sessionId: String, resourceUri: String) {
        synchronized(resourceUpdateStateLock) {
            resourceSubscriptionsBySession[sessionId]?.let { subscriptions ->
                subscriptions.remove(resourceUri)
                if (subscriptions.isEmpty()) {
                    resourceSubscriptionsBySession.remove(sessionId)
                }
            }
        }
    }

    private fun scheduleDocumentResourceUpdate(document: Document) {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        projectResolver.openProjects().forEach { project ->
            val bp = project.basePath
            if (bp != null && file.path.startsWith(bp)) {
                val instanceKey = workspaceInstanceKey()
                val projectKey = workspaceProjectKey(project)
                val relativePath = file.path.removePrefix(bp).trimStart('/')
                scheduleResourceUpdated(workspaceDocumentUri(instanceKey, projectKey, relativePath))
            }
        }
    }

    private fun scheduleResourceUpdated(resourceUri: String) {
        synchronized(resourceUpdateStateLock) {
            pendingResourceUpdateUris.add(resourceUri)
            if (resourceUpdateFlushJob != null) {
                return
            }
            resourceUpdateFlushJob = scope.launch {
                delay(RESOURCE_UPDATE_COALESCE_MILLIS.milliseconds)
                flushPendingResourceUpdates()
            }
        }
    }

    private suspend fun flushPendingResourceUpdates() {
        val resourceUris = synchronized(resourceUpdateStateLock) {
            val snapshot = pendingResourceUpdateUris.toList()
            pendingResourceUpdateUris.clear()
            resourceUpdateFlushJob = null
            snapshot
        }
        val activeServer = server ?: return
        resourceUris.forEach { resourceUri ->
            sendResourceUpdated(activeServer, resourceUri)
        }
    }

    private suspend fun sendResourceUpdated(
        activeServer: Server,
        resourceUri: String,
    ) {
        val sessionIds = subscribedSessionIds(activeServer, resourceUri)
        if (sessionIds.isEmpty()) {
            return
        }
        val notification = ResourceUpdatedNotification(
            ResourceUpdatedNotificationParams(uri = resourceUri),
        )
        sessionIds.forEach { sessionId ->
            runCatching {
                activeServer.sendResourceUpdated(sessionId, notification)
            }.onFailure { error ->
                logger.warn("Failed to send Workspace MCP resource update for $resourceUri to session $sessionId", error)
            }
        }
    }

    private fun subscribedSessionIds(
        activeServer: Server,
        resourceUri: String,
    ): List<String> {
        val activeSessionIds = activeServer.sessions.keys
        return synchronized(resourceUpdateStateLock) {
            resourceSubscriptionsBySession.keys.removeAll { sessionId -> sessionId !in activeSessionIds }
            subscriptionHandlerSessionIds.removeAll { sessionId -> sessionId !in activeSessionIds }
            resourceSubscriptionsBySession
                .filterValues { resourceUri in it }
                .keys
                .toList()
        }
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
        private const val RESOURCE_UPDATE_COALESCE_MILLIS = 100L
    }
}
