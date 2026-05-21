/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.diagnostic.logger
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ResourceUpdatedNotification
import io.modelcontextprotocol.kotlin.sdk.types.ResourceUpdatedNotificationParams

internal class WorkspaceMcpNotificationDispatcher(
    private val subscriptionService: WorkspaceMcpResourceSubscriptionService,
    private val serverSupplier: () -> Server?,
) {
    private val logger = logger<WorkspaceMcpNotificationDispatcher>()

    suspend fun dispatch(invalidations: CoalescedInvalidations) {
        if (invalidations.isEmpty) return
        val activeServer = serverSupplier() ?: return
        invalidations.resourceUris.forEach { uri -> sendResourceUpdated(activeServer, uri) }
        invalidations.resourceListSelectors
            .flatMap { selector -> subscriptionService.sessionIdsForResourceListSelector(activeServer, selector) }
            .toSet()
            .forEach { sessionId -> sendResourceListChanged(activeServer, sessionId) }
        if (invalidations.toolListChanged) {
            activeServer.sessions.keys.forEach { sessionId -> sendToolListChanged(activeServer, sessionId) }
        }
    }

    private suspend fun sendResourceUpdated(activeServer: Server, resourceUri: String) {
        val sessionIds = subscriptionService.subscribedSessionIds(activeServer, resourceUri)
        if (sessionIds.isEmpty()) return
        val notification = ResourceUpdatedNotification(ResourceUpdatedNotificationParams(uri = resourceUri))
        sessionIds.forEach { sessionId ->
            runCatching { activeServer.sendResourceUpdated(sessionId, notification) }
                .onFailure { error -> logger.warn("Failed to send resource update to session $sessionId", error) }
        }
    }

    private suspend fun sendResourceListChanged(activeServer: Server, sessionId: String) {
        runCatching { activeServer.sendResourceListChanged(sessionId) }
            .onFailure { error -> logger.warn("Failed to send resource list changed to session $sessionId", error) }
    }

    private suspend fun sendToolListChanged(activeServer: Server, sessionId: String) {
        runCatching { activeServer.sendToolListChanged(sessionId) }
            .onFailure { error -> logger.warn("Failed to send tool list changed to session $sessionId", error) }
    }
}
