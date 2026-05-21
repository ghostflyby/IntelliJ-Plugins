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
import dev.ghostflyby.mcp.PluginInfo
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
import kotlinx.coroutines.launch

@Service(Service.Level.APP)
internal class WorkspaceMcpSdkServerService(
    private val scope: CoroutineScope,
) {
    private val logger = logger<WorkspaceMcpSdkServerService>()

    private val projectResolver = service<WorkspaceProjectResolver>()
    private val routeSnapshotRef = ResourceRouteSnapshotRef()
    private val sessionState = WorkspaceMcpSessionState { server }
    private val catalog = WorkspaceMcpResourceCatalog(projectResolver)
    private val subscriptionService = WorkspaceMcpResourceSubscriptionService(
        sessionState = sessionState,
    )
    private val notificationDispatcher = WorkspaceMcpNotificationDispatcher(
        subscriptionService = subscriptionService,
        serverSupplier = { server },
    )
    private val invalidationBus = WorkspaceMcpInvalidationBus(
        scope = scope,
        dispatcher = notificationDispatcher,
    )
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
                logger.info("Workspace MCP SDK server started at http://$LOOPBACK_HOST:$port$MCP_ENDPOINT_PATH")
            } finally {
                server = null
                runCatching { createdServer.close() }
                    .onFailure { error -> logger.warn("Failed to close Workspace MCP SDK server", error) }
            }
        }
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
                        invalidationBus.invalidateResourceList()
                    }
                }

                override fun extensionRemoved(extension: WorkspaceMcpFeature, pluginDescriptor: PluginDescriptor) {
                    scope.launch {
                        val activeServer = server ?: return@launch
                        featureCoordinator.unregister(activeServer, extension.featureName)
                        invalidationBus.invalidateResourceList()
                    }
                }
            },
        )
    }

    private fun installWorkspaceSubscriptionHandlers() {
        server?.sessions?.values?.forEach { s -> installWorkspaceSubscriptionHandlers(s) }
    }

    private fun installWorkspaceSubscriptionHandlers(session: ServerSession) {
        val activeServer = server ?: return
        val shouldInstall =
            sessionState.rememberSubscriptionHandler(session.sessionId)
        if (!shouldInstall) return
        session.setRequestHandler<SubscribeRequest>(Method.Defined.ResourcesSubscribe) { request, _ ->
            subscriptionService.recordResourceSubscription(session.sessionId, request.params.uri); EmptyResult()
        }
        session.setRequestHandler<UnsubscribeRequest>(Method.Defined.ResourcesUnsubscribe) { request, _ ->
            subscriptionService.removeResourceSubscription(session.sessionId, request.params.uri); EmptyResult()
        }
        session.setRequestHandler<ListResourcesRequest>(Method.Defined.ResourcesList) { request, _ ->
            catalog.listResources(activeServer.clientConnection(session.sessionId), request)
        }
        session.setRequestHandler<ListResourceTemplatesRequest>(Method.Defined.ResourcesTemplatesList) { request, _ ->
            catalog.listTemplates(activeServer.clientConnection(session.sessionId), request)
        }
        session.setNotificationHandler<RootsListChangedNotification>(
            Method.Defined.NotificationsRootsListChanged,
        ) {
            clearSessionRoots(session.sessionId)
            invalidationBus.invalidateResourceList(ResourceListSelector.Session(session.sessionId))
            CompletableDeferred(Unit)
        }
    }

    private companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val MCP_ENDPOINT_PATH = "/mcp"
    }
}
