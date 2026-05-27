/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.ExtensionPointListener
import com.intellij.openapi.extensions.PluginDescriptor
import dev.ghostflyby.mcp.pluginDescriptor
import dev.ghostflyby.mcp.route.ResourceRouteSnapshotRef
import dev.ghostflyby.mcp.route.SegmentTreeTemplateMatcher
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.*
import io.modelcontextprotocol.kotlin.sdk.utils.ResourceTemplateMatcherFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Service(Service.Level.APP)
internal class WorkspaceMcpSdkServerService(
    private val scope: CoroutineScope,
) {
    private val logger = logger<WorkspaceMcpSdkServerService>()

    private val projectResolver = service<WorkspaceProjectResolver>()
    private val routeSnapshotRef = ResourceRouteSnapshotRef()
    private val sessionState = WorkspaceMcpSessionState { server }
    private val catalog = WorkspaceMcpResourceCatalog()
    private val subscriptionService = WorkspaceMcpResourceSubscriptionService(
        sessionState = sessionState,
    )
    private val invalidationBus = WorkspaceMcpInvalidationBus(scope = scope)
    private val globalGen = MutableStateFlow(0L)
    private val sessionResourcesChanged = MutableStateFlow(PerSessionListChange())

    init {
        invalidationBus.registerGlobalListChanged(ListChangeKind.Resources, globalGen)
        invalidationBus.registerGlobalListChanged(ListChangeKind.Tools, globalGen)
        invalidationBus.registerSessionListChanged(ListChangeKind.Resources, sessionResourcesChanged) { it.sessionIds }
    }

    private val featureCoordinator = WorkspaceMcpFeatureCoordinator(
        parentScope = scope,
        projectResolver = projectResolver,
        catalog = catalog,
        onSnapshotChanged = routeSnapshotRef::set,
        invalidationSink = invalidationBus,
    )

    private val features: List<WorkspaceMcpFeature>
        get() = WORKSPACE_MCP_FEATURE_EP.extensionList

    @Volatile
    private var server: Server? = null

    init {
        subscribeToFeatureEvents()
        scope.launch {
            val createdServer = createServer()
            try {
                val port = service<WorkspaceMcpSdkServerSettings>().port
                server = createdServer
                embeddedServer(CIO, host = LOOPBACK_HOST, port = port) {
                    mcpStreamableHttp(path = MCP_ENDPOINT_PATH) { createdServer }
                }.startSuspend(wait = true)
                @Suppress("HttpUrlsUsage")
                logger.info("Workspace MCP SDK server started at http://$LOOPBACK_HOST:$port$MCP_ENDPOINT_PATH")
            } finally {
                server = null
                runCatching { createdServer.close() }
                    .onFailure { error -> logger.warn("Failed to close Workspace MCP SDK server", error) }
            }
        }
    }

    private fun createServer(): Server {
        val newServer = Server(
            serverInfo = Implementation(name = "workspace-mcp", version = pluginDescriptor.version),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    resources = ServerCapabilities.Resources(subscribe = true, listChanged = true),
                    tools = ServerCapabilities.Tools(listChanged = true),
                ),
                resourceTemplateMatcherFactory = ResourceTemplateMatcherFactory { template ->
                    SegmentTreeTemplateMatcher(template, routeSnapshotRef)
                },
            ).apply { concurrentMessageHandling = true },
            instructions = "Workspace MCP exposes IntelliJ VFS and editor document snapshots as MCP resources.",
        ) {
            featureCoordinator.registerInitial(this, features)
        }
        newServer.onConnect { session ->
            session.setRequestHandler<SubscribeRequest>(Method.Defined.ResourcesSubscribe) { request, _ ->
                subscriptionService.recordResourceSubscription(session.sessionId, request.params.uri); EmptyResult()
            }
            session.setRequestHandler<UnsubscribeRequest>(Method.Defined.ResourcesUnsubscribe) { request, _ ->
                subscriptionService.removeResourceSubscription(session.sessionId, request.params.uri); EmptyResult()
            }
            session.setRequestHandler<ListResourcesRequest>(Method.Defined.ResourcesList) { request, _ ->
                catalog.listResources(newServer.clientConnection(session.sessionId), request)
            }
            session.setRequestHandler<ListResourceTemplatesRequest>(Method.Defined.ResourcesTemplatesList) { request, _ ->
                catalog.listTemplates(newServer.clientConnection(session.sessionId), request)
            }
            session.setNotificationHandler<RootsListChangedNotification>(
                Method.Defined.NotificationsRootsListChanged,
            ) {
                sessionState.clearRoots(session.sessionId)
                sessionResourcesChanged.update { it.next(session.sessionId) }
                CompletableDeferred(Unit)
            }

            // Per-session notification collectors
            val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            session.onClose { sessionScope.cancel() }

            sessionScope.launch {
                invalidationBus.resourceUpdateBatches.collect { uris ->
                    uris.filter { subscriptionService.isSubscribed(session.sessionId, it) }
                        .forEach { uri ->
                            runCatching {
                                session.sendResourceUpdated(
                                    ResourceUpdatedNotification(ResourceUpdatedNotificationParams(uri = uri))
                                )
                            }.onFailure { error ->
                                logger.warn("Failed to send resource update to session ${session.sessionId}", error)
                            }
                        }
                }
            }
            sessionScope.launch {
                invalidationBus.listChangedBatches.collect { events ->
                    events.forEach { (kind, selector) ->
                        when (selector) {
                            SessionSelector.AllSessions -> notifyListChanged(session, kind)
                            is SessionSelector.Sessions if session.sessionId in selector.sessionIds ->
                                notifyListChanged(session, kind)
                            else -> {}
                        }
                    }
                }
            }
        }
        return newServer
    }

    private suspend fun notifyListChanged(session: ServerSession, kind: ListChangeKind) {
        runCatching {
            when (kind) {
                ListChangeKind.Resources -> session.sendResourceListChanged()
                ListChangeKind.Tools -> session.sendToolListChanged()
            }
        }.onFailure { error ->
            logger.warn("Failed to send list changed notification to session ${session.sessionId}", error)
        }
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
                        globalGen.update { it + 1 }
                    }
                }

                override fun extensionRemoved(extension: WorkspaceMcpFeature, pluginDescriptor: PluginDescriptor) {
                    scope.launch {
                        val activeServer = server ?: return@launch
                        featureCoordinator.unregister(activeServer, extension.featureName)
                        globalGen.update { it + 1 }
                    }
                }
            },
        )
    }

    private companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val MCP_ENDPOINT_PATH = "/mcp"
    }
}

private data class PerSessionListChange(
    val generation: Long = 0L,
    val sessionIds: Set<String> = emptySet(),
) {
    fun next(sessionId: String): PerSessionListChange = copy(
        generation = generation + 1,
        sessionIds = setOf(sessionId),
    )
}
