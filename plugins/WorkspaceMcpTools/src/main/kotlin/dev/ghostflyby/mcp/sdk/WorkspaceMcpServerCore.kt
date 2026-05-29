/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import dev.ghostflyby.mcp.route.ResourceRouteSnapshotRef
import dev.ghostflyby.mcp.route.SegmentTreeTemplateMatcher
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.types.*
import io.modelcontextprotocol.kotlin.sdk.utils.ResourceTemplateMatcherFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

internal class WorkspaceMcpServerCore(
    private val parentScope: CoroutineScope,
    private val projectResolver: WorkspaceProjectProvider,
    private val serverInfo: Implementation,
    private val instructions: String,
    private val initialFeatures: List<WorkspaceMcpFeature>,
    private val stateFlows: WorkspaceMcpStateFlows,
    private val sessionState: WorkspaceMcpSessionState = WorkspaceMcpSessionState(),
    private val sessionRoots: WorkspaceMcpSessionRoots = WorkspaceMcpSessionRoots(),
    private val instanceKeyProvider: () -> String = ::workspaceInstanceKey,
    private val logger: WorkspaceMcpCoreLogger = WorkspaceMcpCoreLogger.Noop,
) {
    private val routeSnapshotRef = ResourceRouteSnapshotRef()
    private val catalog = WorkspaceMcpResourceCatalog(
        projectResolver = projectResolver,
        instanceKeyProvider = instanceKeyProvider,
    )
    private val subscriptionService = WorkspaceMcpResourceSubscriptionService(
        sessionState = sessionState,
    )
    private val invalidationBus = WorkspaceMcpInvalidationBus(scope = parentScope)
    private val globalGen = MutableStateFlow(0L)
    private val callFactory = workspaceMcpCallFactory(projectResolver)

    private val featureCoordinator = WorkspaceMcpFeatureCoordinator(
        parentScope = parentScope,
        projectResolver = projectResolver,
        catalog = catalog,
        onSnapshotChanged = routeSnapshotRef::set,
        invalidationSink = invalidationBus,
        callFactory = callFactory,
        instanceKeyProvider = instanceKeyProvider,
        logger = logger,
    )

    val roots: StateFlow<WorkspaceMcpSessionRootsState> get() = sessionRoots.state

    val server: Server = createServer()

    init {
        invalidationBus.registerResourceUpdates(stateFlows.resourceUpdates)
        invalidationBus.registerGlobalListChanged(ListChangeKind.Resources, stateFlows.globalResourceListChanges)
        invalidationBus.registerGlobalListChanged(ListChangeKind.Tools, globalGen)
        invalidationBus.registerSessionListChanged(
            ListChangeKind.Resources,
            stateFlows.perSessionResourceListChanges,
        )
        invalidationBus.registerSessionListChanged(
            ListChangeKind.Resources,
            sessionRoots.state,
        ) { it.changedGenerationsBySession.keys }
    }

    fun hasResourceSubscriptions(): Boolean = sessionState.hasResourceSubscriptions()

    suspend fun close() {
        server.close()
    }

    fun register(feature: WorkspaceMcpFeature) {
        featureCoordinator.register(server, feature)
        globalGen.update { it + 1 }
    }

    fun unregister(featureName: String) {
        featureCoordinator.unregister(server, featureName)
        globalGen.update { it + 1 }
    }

    fun syncResources() {
        featureCoordinator.syncResources(server)
    }

    private fun createServer(): Server {
        val createdServer = Server(
            serverInfo = serverInfo,
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    resources = ServerCapabilities.Resources(subscribe = true, listChanged = true),
                    tools = ServerCapabilities.Tools(listChanged = true),
                ),
                resourceTemplateMatcherFactory = ResourceTemplateMatcherFactory { template ->
                    SegmentTreeTemplateMatcher(template, routeSnapshotRef)
                },
            ).apply { concurrentMessageHandling = true },
            instructions = instructions,
        ) {
            featureCoordinator.registerInitial(this, initialFeatures)
        }
        createdServer.onConnect { session ->
            wireSession(createdServer, session)
        }
        return createdServer
    }

    private fun wireSession(activeServer: Server, session: ServerSession) {
        sessionState.recordSessionConnected(session.sessionId)
        sessionRoots.recordSessionConnected(session.sessionId)
        session.setRequestHandler<SubscribeRequest>(Method.Defined.ResourcesSubscribe) { request, _ ->
            subscriptionService.recordResourceSubscription(session.sessionId, request.params.uri)
            EmptyResult()
        }
        session.setRequestHandler<UnsubscribeRequest>(Method.Defined.ResourcesUnsubscribe) { request, _ ->
            subscriptionService.removeResourceSubscription(session.sessionId, request.params.uri)
            EmptyResult()
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
            sessionRoots.recordRootsListChanged(session.sessionId)
            stateFlows.sessionResourcesChanged(session.sessionId)
            CompletableDeferred(Unit)
        }

        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        session.onClose {
            sessionState.recordSessionClosed(session.sessionId)
            sessionRoots.recordSessionClosed(session.sessionId)
            sessionScope.cancel()
        }

        sessionScope.launch {
            invalidationBus.resourceUpdateBatches.collect { uris ->
                uris.filter { subscriptionService.isSubscribed(session.sessionId, it) }
                    .forEach { uri ->
                        runCatching {
                            session.sendResourceUpdated(
                                ResourceUpdatedNotification(ResourceUpdatedNotificationParams(uri = uri)),
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
}

internal interface WorkspaceMcpCoreLogger {
    fun warn(message: String, error: Throwable?)

    fun warn(message: String) {
        warn(message, null)
    }

    object Noop : WorkspaceMcpCoreLogger {
        override fun warn(message: String, error: Throwable?) = Unit
    }
}
