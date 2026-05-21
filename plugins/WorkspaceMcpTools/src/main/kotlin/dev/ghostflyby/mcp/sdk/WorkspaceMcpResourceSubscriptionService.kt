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
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

internal class WorkspaceMcpResourceSubscriptionService(
    private val sessionState: WorkspaceMcpSessionState,
    private val serverSupplier: () -> Server?,
    private val scope: CoroutineScope,
) : WorkspaceMcpInvalidationSink {
    private val logger = logger<WorkspaceMcpResourceSubscriptionService>()
    private val pendingUpdateRef = AtomicReference<Set<String>>(emptySet())
    private var resourceUpdateFlushJob: Job? = null
    private val pendingListChangedRef = AtomicReference<Set<String>>(emptySet())
    private var listChangedFlushJob: Job? = null
    private val pendingToolListChangedRef = AtomicReference<Set<String>>(emptySet())
    private var toolListChangedFlushJob: Job? = null

    fun recordResourceSubscription(sessionId: String, resourceUri: String) {
        if (!isWorkspaceResourceUri(resourceUri)) return
        sessionState.recordResourceSubscription(sessionId, resourceUri)
    }

    fun removeResourceSubscription(sessionId: String, resourceUri: String) {
        sessionState.removeResourceSubscription(sessionId, resourceUri)
    }

    override fun invalidateResource(uri: String) {
        if (!isWorkspaceResourceUri(uri)) return
        scheduleResourceUpdated(uri)
    }

    override fun invalidateResourceList(selector: ResourceListSelector) {
        val activeServer = serverSupplier() ?: return
        val sessionIds = when (selector) {
            ResourceListSelector.AllSessions -> activeServer.sessions.keys
            is ResourceListSelector.Session -> setOf(selector.sessionId)
            is ResourceListSelector.Uri -> sessionState.sessionIdsForResourceListSelector(
                activeServer.sessions.keys,
                selector,
            )

            is ResourceListSelector.UriPrefix -> sessionState.sessionIdsForResourceListSelector(
                activeServer.sessions.keys,
                selector,
            )
        }
        scheduleListChanged(sessionIds)
    }

    override fun invalidateToolList() {
        val activeServer = serverSupplier() ?: return
        scheduleToolListChanged(activeServer.sessions.keys)
    }

    internal fun subscribedSessionIds(activeServer: Server, resourceUri: String): List<String> {
        return sessionState.subscribedSessionIds(activeServer.sessions.keys, resourceUri)
    }

    private fun scheduleResourceUpdated(resourceUri: String) {
        while (true) {
            val current = pendingUpdateRef.get()
            if (pendingUpdateRef.compareAndSet(current, current + resourceUri)) break
        }
        if (resourceUpdateFlushJob != null) return
        resourceUpdateFlushJob = scope.launch {
            delay(RESOURCE_UPDATE_COALESCE_MILLIS.milliseconds)
            flushPendingResourceUpdates()
        }
    }

    private fun scheduleListChanged(sessionIds: Set<String>) {
        if (sessionIds.isEmpty()) return
        while (true) {
            val current = pendingListChangedRef.get()
            if (pendingListChangedRef.compareAndSet(current, current + sessionIds)) break
        }
        if (listChangedFlushJob != null) return
        listChangedFlushJob = scope.launch {
            delay(RESOURCE_LIST_CHANGED_COALESCE_MILLIS.milliseconds)
            flushPendingListChanged()
        }
    }

    private fun scheduleToolListChanged(sessionIds: Set<String>) {
        if (sessionIds.isEmpty()) return
        while (true) {
            val current = pendingToolListChangedRef.get()
            if (pendingToolListChangedRef.compareAndSet(current, current + sessionIds)) break
        }
        if (toolListChangedFlushJob != null) return
        toolListChangedFlushJob = scope.launch {
            delay(TOOL_LIST_CHANGED_COALESCE_MILLIS.milliseconds)
            flushPendingToolListChanged()
        }
    }

    private suspend fun flushPendingResourceUpdates() {
        val resourceUris = pendingUpdateRef.getAndSet(emptySet()).toList()
        resourceUpdateFlushJob = null
        val activeServer = serverSupplier() ?: return
        resourceUris.forEach { uri -> sendResourceUpdated(activeServer, uri) }
    }

    private suspend fun flushPendingListChanged() {
        val sessionIds = pendingListChangedRef.getAndSet(emptySet())
        listChangedFlushJob = null
        val activeServer = serverSupplier() ?: return
        val aliveIds = activeServer.sessions.keys
        sessionIds.intersect(aliveIds).forEach { sessionId ->
            runCatching { activeServer.sendResourceListChanged(sessionId) }
                .onFailure { error ->
                    logger.warn("Failed to send resource list changed to session $sessionId", error)
                }
        }
    }

    private suspend fun flushPendingToolListChanged() {
        val sessionIds = pendingToolListChangedRef.getAndSet(emptySet())
        toolListChangedFlushJob = null
        val activeServer = serverSupplier() ?: return
        val aliveIds = activeServer.sessions.keys
        sessionIds.intersect(aliveIds).forEach { sessionId ->
            runCatching { activeServer.sendToolListChanged(sessionId) }
                .onFailure { error ->
                    logger.warn("Failed to send tool list changed to session $sessionId", error)
                }
        }
    }

    private suspend fun sendResourceUpdated(activeServer: Server, resourceUri: String) {
        val sessionIds = subscribedSessionIds(activeServer, resourceUri)
        if (sessionIds.isEmpty()) return
        val notification = ResourceUpdatedNotification(ResourceUpdatedNotificationParams(uri = resourceUri))
        sessionIds.forEach { sessionId ->
            runCatching { activeServer.sendResourceUpdated(sessionId, notification) }
        }
    }

    // -- URI helpers (private to this service) --

    internal companion object {
        private const val RESOURCE_UPDATE_COALESCE_MILLIS = 100L
        private const val RESOURCE_LIST_CHANGED_COALESCE_MILLIS = 100L
        private const val TOOL_LIST_CHANGED_COALESCE_MILLIS = 100L

        private const val WORKSPACE_URI_SCHEME = "ij-workspace://"
        private const val PROJECTS_SEGMENT = "/projects/"
        private const val KIND_VFS = "vfs"
        private const val KIND_FILES = "files"

        private enum class Kind { FILES, VFS }

        private data class DecodedWorkspaceUri(
            val instanceKey: String,
            val projectKey: String?,
            val kind: Kind,
            val tail: String,
        )

        internal fun isWorkspaceResourceUri(uri: String): Boolean =
            tryDecodeWorkspaceResourceUri(uri) != null

        private fun tryDecodeWorkspaceResourceUri(uri: String): DecodedWorkspaceUri? {
            if (!uri.startsWith(WORKSPACE_URI_SCHEME)) return null
            val afterScheme = uri.removePrefix(WORKSPACE_URI_SCHEME)
            val vfsPrefix = "$KIND_VFS/"
            val firstSlash = afterScheme.indexOf('/')
            if (firstSlash < 0) return null
            val instanceKey = afterScheme.substring(0, firstSlash)
            if (instanceKey.isBlank()) return null
            val afterInstance = afterScheme.substring(firstSlash + 1)
            if (afterInstance.startsWith(vfsPrefix)) {
                val tail = afterInstance.removePrefix(vfsPrefix)
                if (tail.isBlank()) return null
                return DecodedWorkspaceUri(
                    instanceKey = instanceKey,
                    projectKey = null,
                    kind = Kind.VFS,
                    tail = tail,
                )
            }
            val projectsIdx = afterScheme.indexOf(PROJECTS_SEGMENT)
            if (projectsIdx < 0) return null
            val afterProjects = afterScheme.substring(projectsIdx + PROJECTS_SEGMENT.length)
            val projectSlash = afterProjects.indexOf('/')
            if (projectSlash < 0) return null
            val projectKey = afterProjects.substring(0, projectSlash)
            if (projectKey.isBlank()) return null
            val afterProjectKey = afterProjects.substring(projectSlash + 1)
            val kindEnd = afterProjectKey.indexOf('/')
            if (kindEnd < 0) return null
            val kindStr = afterProjectKey.substring(0, kindEnd)
            val kind = when (kindStr) {
                KIND_FILES -> Kind.FILES
                else -> return null
            }
            val tail = afterProjectKey.substring(kindEnd + 1)
            if (tail.isBlank()) return null
            return DecodedWorkspaceUri(instanceKey = instanceKey, projectKey = projectKey, kind = kind, tail = tail)
        }
    }
}
