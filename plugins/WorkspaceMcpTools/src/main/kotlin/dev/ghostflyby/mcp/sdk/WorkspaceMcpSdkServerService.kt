/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
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
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import dev.ghostflyby.mcp.PluginInfo
import dev.ghostflyby.mcp.route.ResourceRouteSnapshotRef
import dev.ghostflyby.mcp.route.SegmentTreeTemplateMatcher
import dev.ghostflyby.mcp.resource.tryDecodeWorkspaceResourceUri
import dev.ghostflyby.mcp.resource.workspaceDocumentUri
import dev.ghostflyby.mcp.resource.workspaceVfsUri
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.*
import io.modelcontextprotocol.kotlin.sdk.utils.ResourceTemplateMatcherFactory
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.milliseconds

@Service(Service.Level.APP)
internal class WorkspaceMcpSdkServerService(
    private val scope: CoroutineScope,
) : Disposable {
    private val logger = logger<WorkspaceMcpSdkServerService>()
    private val projectResolver = service<WorkspaceProjectResolver>()
    private val routeSnapshotRef = ResourceRouteSnapshotRef()
    private val sessionState = WorkspaceMcpSessionState { server }
    private val catalog = WorkspaceMcpResourceCatalog(sessionState, projectResolver)
    private val eventBus = WorkspaceMcpResourceEventBus(scope) { server }
    private val primitiveRegistry = WorkspaceMcpPrimitiveRegistry(sessionState)
    private val featureCoordinator = WorkspaceMcpFeatureCoordinator(
        parentScope = scope,
        projectResolver = projectResolver,
        primitiveRegistry = primitiveRegistry,
        catalog = catalog,
        onSnapshotChanged = routeSnapshotRef::set,
    )

    private val features: List<WorkspaceMcpFeature>
        get() = WORKSPACE_MCP_FEATURE_EP.extensionList

    private val resourceUpdateStateLock = Any()
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
                val createdServer = createServer()
                val port = service<WorkspaceMcpSdkServerSettings>().port
                val started = embeddedServer(CIO, host = LOOPBACK_HOST, port = port) {
                    mcpStreamableHttp(path = MCP_ENDPOINT_PATH) { createdServer }
                }.start(wait = false)
                server = createdServer
                engine = started
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

    private fun createServer(): Server {
        val server = Server(
            serverInfo = Implementation(name = "workspace-mcp", version = PluginInfo.version),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    resources = ServerCapabilities.Resources(subscribe = true, listChanged = true),
                    tools = ServerCapabilities.Tools(listChanged = true),
                ),
                resourceTemplateMatcherFactory = ResourceTemplateMatcherFactory { template ->
                    SegmentTreeTemplateMatcher(template, routeSnapshotRef)
                },
            ),
            instructions = "Workspace MCP exposes IntelliJ VFS and editor document snapshots as MCP resources.",
        ) {
            featureCoordinator.registerInitial(this, features)
            onConnect { installWorkspaceSubscriptionHandlers() }
        }
        return server
    }

    internal suspend fun getSessionRoots(sessionId: String): List<String> {
        return sessionState.getRoots(sessionId)
    }

    internal fun clearSessionRoots(sessionId: String) {
        sessionState.clearRoots(sessionId)
    }

    private fun subscribeToFeatureEvents() {
        WORKSPACE_MCP_FEATURE_EP.point.addExtensionPointListener(
            scope,
            false,
            object : ExtensionPointListener<WorkspaceMcpFeature> {
                override fun extensionAdded(extension: WorkspaceMcpFeature, pluginDescriptor: PluginDescriptor) {
                    scope.launch {
                        val activeServer = server ?: return@launch
                        featureCoordinator.register(activeServer, extension)
                        scheduleResourceListChanged()
                    }
                }

                override fun extensionRemoved(extension: WorkspaceMcpFeature, pluginDescriptor: PluginDescriptor) {
                    scope.launch {
                        val activeServer = server ?: return@launch
                        featureCoordinator.unregister(activeServer, extension.featureName)
                        scheduleResourceListChanged()
                    }
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
                }
            },
        )
    }

    private fun installWorkspaceSubscriptionHandlers() {
        server?.sessions?.values?.forEach { s -> installWorkspaceSubscriptionHandlers(s) }
    }

    private fun installWorkspaceSubscriptionHandlers(session: ServerSession) {
        val shouldInstall =
            sessionState.rememberSubscriptionHandler(session.sessionId)
        if (!shouldInstall) return
        session.setRequestHandler<SubscribeRequest>(Method.Defined.ResourcesSubscribe) { request, _ ->
            recordResourceSubscription(session.sessionId, request.params.uri); EmptyResult()
        }
        session.setRequestHandler<UnsubscribeRequest>(Method.Defined.ResourcesUnsubscribe) { request, _ ->
            removeResourceSubscription(session.sessionId, request.params.uri); EmptyResult()
        }
        session.setRequestHandler<ListResourcesRequest>(Method.Defined.ResourcesList) { request, _ ->
            catalog.listResources(session.sessionId, request)
        }
        session.setRequestHandler<ListResourceTemplatesRequest>(Method.Defined.ResourcesTemplatesList) { request, _ ->
            catalog.listTemplates(session.sessionId, request)
        }
        session.setNotificationHandler<RootsListChangedNotification>(
            Method.Defined.NotificationsRootsListChanged,
        ) {
            clearSessionRoots(session.sessionId)
            eventBus.scheduleSessionListChanged(session.sessionId)
            CompletableDeferred(Unit)
        }
    }

    private fun recordResourceSubscription(sessionId: String, resourceUri: String) {
        if (tryDecodeWorkspaceResourceUri(resourceUri) == null) return
        sessionState.recordResourceSubscription(sessionId, resourceUri)
    }

    private fun removeResourceSubscription(sessionId: String, resourceUri: String) {
        sessionState.removeResourceSubscription(sessionId, resourceUri)
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

    internal fun scheduleResourceListChanged() {
        eventBus.scheduleAllSessionsListChanged()
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
        return sessionState.subscribedSessionIds(activeServer.sessions.keys, resourceUri)
    }

    private companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val MCP_ENDPOINT_PATH = "/mcp"
        private const val RESOURCE_UPDATE_COALESCE_MILLIS = 100L
    }
}
