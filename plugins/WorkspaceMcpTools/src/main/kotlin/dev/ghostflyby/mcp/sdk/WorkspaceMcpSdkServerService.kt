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
import com.intellij.openapi.extensions.ExtensionPointListener
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import dev.ghostflyby.mcp.resource.*
import dev.ghostflyby.mcp.resource.segment.ResourceSegmentRegistry
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds

@Service(Service.Level.APP)
internal class WorkspaceMcpSdkServerService(
    private val scope: CoroutineScope,
) : Disposable {
    private val logger = logger<WorkspaceMcpSdkServerService>()
    private val projectResolver = WorkspaceProjectResolver()
    private val segmentRegistry = ResourceSegmentRegistry()
    private val resourceReader = WorkspaceResourceReader(projectResolver)
    private val requestRunner = WorkspaceMcpRequestRunner(projectResolver)

    private val features: List<WorkspaceMcpFeature>
        get() = WORKSPACE_MCP_FEATURE_EP.extensionList

    private val resourceRegistryMutex = Mutex()
    private var listableResourceUris: Set<String> = emptySet()
    private val featureRegistrationLock = Any()
    private val featureRegistrations = linkedMapOf<String, WorkspaceMcpFeatureRegistration>()
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
        subscribeToResourceUpdateEvents()
        subscribeToFeatureEvents()
        scope.launch {
            runCatching {
                val listableResources = listWorkspaceResources()
                val createdServer = createServer(listableResources)
                val port = service<WorkspaceMcpSdkServerSettings>().port
                val started = embeddedServer(CIO, host = LOOPBACK_HOST, port = port) {
                    mcpStreamableHttp(path = MCP_ENDPOINT_PATH) { createdServer }
                }.start(wait = false)
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
        scope.launch { closeServer() }
    }

    private suspend fun closeServer() {
        val stoppedEngine = engine
        val stoppedServer = server
        engine = null
        server = null
        runCatching { stoppedServer?.close() }
            .onFailure { error -> logger.warn("Failed to close Workspace MCP SDK server", error) }
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

    private suspend fun refreshListableResources(
        activeServer: Server,
        nextResources: List<WorkspaceListableResource>,
    ) {
        resourceRegistryMutex.withLock {
            val nextUris = nextResources.mapTo(mutableSetOf()) { it.uri }
            if (nextUris == listableResourceUris) return
            val removedUris = listableResourceUris - nextUris
            val addedResources = nextResources.filter { it.uri !in listableResourceUris }
            removedUris.forEach { uri -> activeServer.removeResource(uri) }
            addedResources.forEach { entry ->
                activeServer.addResource(
                    uri = entry.uri, name = entry.name, description = entry.description, mimeType = entry.mimeType,
                ) { readResource(entry.uri, this.sessionId) }
            }
            listableResourceUris = nextUris
        }
    }

    private suspend fun listWorkspaceResources(): List<WorkspaceListableResource> =
        features.flatMap { it.computeListableResources(featureContext()) }.distinctBy { it.uri }

    /**
     * Shared read callback provided to features during registration.
     * - Core listable resources (server/info, projects, projects/{key}) shortcut.
     * - Project-scoped resources delegate to [requestRunner] for context installation.
     */
    private suspend fun readResource(resourceUri: String, sessionId: String?): ReadResourceResult {
        // Segment-based resource matching (handles core server/info, projects, and feature resources)
        val segmentMatch = segmentRegistry.match(resourceUri)
        if (segmentMatch != null) {
            return when (val seg = segmentMatch.segment) {
                is dev.ghostflyby.mcp.resource.segment.StaticSegment -> {
                    seg.handler?.invoke(segmentMatch.params, segmentMatch.anc)
                        ?: ReadResourceResult(contents = emptyList())
                }
                is dev.ghostflyby.mcp.resource.segment.TemplateSegment -> {
                    seg.handler(segmentMatch.params, segmentMatch.anc)
                }
            }
        }

        val decoded = tryDecodeWorkspaceResourceUri(resourceUri)
        return withWorkspaceMcpCallContext(sessionId = sessionId, instanceKey = workspaceInstanceKey()) {
            if (decoded != null) {
                requestRunner.runResourceRead(sessionId = sessionId, decoded = decoded) {
                    resourceReader.readWorkspaceResource(resourceUri).toReadResourceResult()
                }
            } else {
                resourceReader.readWorkspaceResource(resourceUri).toReadResourceResult()
            }
        }
    }

    private fun WorkspaceResourceTextContent.toReadResourceResult(): ReadResourceResult =
        ReadResourceResult(contents = listOf(TextResourceContents(uri = uri, mimeType = mimeType, text = text)))

    private fun createServer(listableResources: List<WorkspaceListableResource>): Server {
        val server = Server(
            serverInfo = Implementation(name = "workspace-mcp", version = "1.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    resources = ServerCapabilities.Resources(subscribe = true, listChanged = true),
                    tools = ServerCapabilities.Tools(listChanged = true),
                ),
                resourceTemplateMatcherFactory = WorkspaceResourceTemplateMatcherFactory,
            ),
            instructions = "Workspace MCP exposes IntelliJ VFS and editor document snapshots as MCP resources.",
        ) {
            listableResources.forEach { entry ->
                addResource(
                    uri = entry.uri,
                    name = entry.name,
                    description = entry.description,
                    mimeType = entry.mimeType,
                ) {
                    readResource(entry.uri, this.sessionId)
                }
            }
            features.forEach { feature ->
                registerFeature(this, feature)
            }
            onConnect { installWorkspaceSubscriptionHandlers() }
        }
        return server
    }

    private fun featureContext(): WorkspaceMcpFeatureContext = WorkspaceMcpFeatureContext(
        projectResolver = projectResolver,
        readResource = ::readResource,
    )

    private fun featureRegistrationContext(
        server: Server,
        feature: WorkspaceMcpFeature,
        featureScope: CoroutineScope,
    ): WorkspaceMcpFeatureRegistrationContext = WorkspaceMcpFeatureRegistrationContext(
        projectResolver = projectResolver,
        requestRunner = requestRunner,
        server = server,
        featureScope = featureScope,
        featureName = feature.featureName,
        readResource = ::readResource,
    )

    private fun subscribeToFeatureEvents() {
        WORKSPACE_MCP_FEATURE_EP.point.addExtensionPointListener(
            scope,
            false,
            object : ExtensionPointListener<WorkspaceMcpFeature> {
                override fun extensionAdded(extension: WorkspaceMcpFeature, pluginDescriptor: PluginDescriptor) {
                    scope.launch {
                        val activeServer = server ?: return@launch
                        registerFeature(activeServer, extension)
                        refreshListableResources()
                    }
                }

                override fun extensionRemoved(extension: WorkspaceMcpFeature, pluginDescriptor: PluginDescriptor) {
                    scope.launch {
                        val activeServer = server ?: return@launch
                        unregisterFeature(activeServer, extension.featureName)
                        refreshListableResources()
                    }
                }
            },
        )
    }

    private fun registerFeature(activeServer: Server, feature: WorkspaceMcpFeature) {
        synchronized(featureRegistrationLock) {
            if (feature.featureName in featureRegistrations) {
                logger.warn("Workspace MCP feature ${feature.featureName} is already registered")
                return
            }
        }

        val featureJob = SupervisorJob(scope.coroutineContext[Job])
        val featureScope = CoroutineScope(scope.coroutineContext + featureJob)
        val registration = runCatching {
            feature.register(featureRegistrationContext(activeServer, feature, featureScope))
        }.onFailure { error ->
            featureJob.cancel()
            logger.warn("Failed to register Workspace MCP feature ${feature.featureName}", error)
        }.getOrNull() ?: return

        synchronized(featureRegistrationLock) {
            val previous = featureRegistrations.putIfAbsent(feature.featureName, registration)
            if (previous != null) {
                featureJob.cancel()
                logger.warn("Workspace MCP feature ${feature.featureName} was registered concurrently")
            }
        }
    }

    private fun unregisterFeature(activeServer: Server, featureName: String) {
        val registration = synchronized(featureRegistrationLock) {
            featureRegistrations.remove(featureName)
        } ?: return

        // Remove feature's segments from the global tree
        segmentRegistry.removeTree(registration.segmentIds)

        registration.job.cancel()
        registration.registeredTools.forEach { name ->
            runCatching { activeServer.removeTool(name) }
                .onFailure { error ->
                    logger.warn(
                        "Failed to remove Workspace MCP tool $name for feature $featureName",
                        error,
                    )
                }
        }
        registration.registeredTemplates.forEach { uriTemplate ->
            runCatching { activeServer.removeResourceTemplate(uriTemplate) }
                .onFailure { error ->
                    logger.warn(
                        "Failed to remove Workspace MCP resource template $uriTemplate for feature $featureName",
                        error,
                    )
                }
        }
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
                    events.mapNotNull { it.file?.url }
                        .flatMap { url ->
                            projectResolver.openProjects().mapNotNull { project ->
                                val bp = project.basePath
                                if (bp != null && url.startsWith("file://$bp")) {
                                    workspaceVfsUri(workspaceInstanceKey(), workspaceProjectKey(project), url)
                                } else if (url.startsWith("file://") && projectResolver.openProjects().size == 1) {
                                    workspaceVfsUri(workspaceInstanceKey(), workspaceProjectKey(project), url)
                                } else null
                            }
                        }.distinct().forEach(::scheduleResourceUpdated)
                    refreshListableResources()
                }
            },
        )
    }

    private fun installWorkspaceSubscriptionHandlers() {
        server?.sessions?.values?.forEach { s -> installWorkspaceSubscriptionHandlers(s) }
    }

    private fun installWorkspaceSubscriptionHandlers(session: ServerSession) {
        val shouldInstall =
            synchronized(resourceUpdateStateLock) { subscriptionHandlerSessionIds.add(session.sessionId) }
        if (!shouldInstall) return
        session.setRequestHandler<SubscribeRequest>(Method.Defined.ResourcesSubscribe) { request, _ ->
            recordResourceSubscription(session.sessionId, request.params.uri); EmptyResult()
        }
        session.setRequestHandler<UnsubscribeRequest>(Method.Defined.ResourcesUnsubscribe) { request, _ ->
            removeResourceSubscription(session.sessionId, request.params.uri); EmptyResult()
        }
    }

    private fun recordResourceSubscription(sessionId: String, resourceUri: String) {
        if (tryDecodeWorkspaceResourceUri(resourceUri) == null) return
        synchronized(resourceUpdateStateLock) {
            resourceSubscriptionsBySession.getOrPut(sessionId) { linkedSetOf() }.add(resourceUri)
        }
    }

    private fun removeResourceSubscription(sessionId: String, resourceUri: String) {
        synchronized(resourceUpdateStateLock) {
            resourceSubscriptionsBySession[sessionId]?.let { subs ->
                subs.remove(resourceUri); if (subs.isEmpty()) resourceSubscriptionsBySession.remove(sessionId)
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
            if (resourceUpdateFlushJob != null) return
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
        resourceUris.forEach { uri -> sendResourceUpdated(activeServer, uri) }
    }

    private suspend fun sendResourceUpdated(activeServer: Server, resourceUri: String) {
        val sessionIds = subscribedSessionIds(activeServer, resourceUri)
        if (sessionIds.isEmpty()) return
        val notification = ResourceUpdatedNotification(ResourceUpdatedNotificationParams(uri = resourceUri))
        sessionIds.forEach { sessionId ->
            runCatching { activeServer.sendResourceUpdated(sessionId, notification) }
                .onFailure { error ->
                    logger.warn(
                        "Failed to send Workspace MCP resource update for $resourceUri to session $sessionId",
                        error,
                    )
                }
        }
    }

    private fun subscribedSessionIds(activeServer: Server, resourceUri: String): List<String> {
        val activeSessionIds = activeServer.sessions.keys
        return synchronized(resourceUpdateStateLock) {
            resourceSubscriptionsBySession.keys.removeAll { it !in activeSessionIds }
            subscriptionHandlerSessionIds.removeAll { it !in activeSessionIds }
            resourceSubscriptionsBySession.filterValues { resourceUri in it }.keys.toList()
        }
    }

    private companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val MCP_ENDPOINT_PATH = "/mcp"
        private const val RESOURCE_UPDATE_COALESCE_MILLIS = 100L
    }
}

internal object ProjectManagerListener1 : ProjectManagerListener {
    override fun projectClosed(project: Project) {
        service<WorkspaceMcpSdkServerService>().refreshListableResources()
    }
}

internal object FileEditorManagerListener1 : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        service<WorkspaceMcpSdkServerService>().refreshListableResources()
    }

    override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
        service<WorkspaceMcpSdkServerService>().refreshListableResources()
    }
}


internal object ModuleRootListener1 : ModuleRootListener {
    override fun rootsChanged(event: ModuleRootEvent) {
        service<WorkspaceMcpSdkServerService>().refreshListableResources()
    }
}
