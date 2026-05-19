/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import io.modelcontextprotocol.kotlin.sdk.server.Server
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal class WorkspaceMcpSessionState(
    private val serverProvider: () -> Server?,
) {
    private val rootsCache = ConcurrentHashMap<String, List<String>>()
    private val subscriptionLock = Any()
    private val resourceSubscriptionsBySession = linkedMapOf<String, MutableSet<String>>()
    private val subscriptionHandlerSessionIds = linkedSetOf<String>()
    private val rootsVersion = AtomicLong(0L)

    suspend fun getRoots(sessionId: String): List<String> {
        return rootsCache.getOrPut(sessionId) {
            val activeServer = serverProvider() ?: return@getOrPut emptyList()
            runCatching { activeServer.listRoots(sessionId) }
                .getOrNull()
                ?.roots
                ?.map { it.uri.removePrefix("file://") }
                ?: emptyList()
        }
    }

    fun getRootsVersion(): Long = rootsVersion.get()

    fun clearRoots(sessionId: String) {
        rootsCache.remove(sessionId)
        rootsVersion.incrementAndGet()
    }

    fun clearAllRoots() {
        rootsCache.clear()
        rootsVersion.incrementAndGet()
    }

    fun rememberSubscriptionHandler(sessionId: String): Boolean =
        subscriptionHandlerSessionIds.add(sessionId)

    fun recordResourceSubscription(sessionId: String, resourceUri: String) {
        synchronized(subscriptionLock) {
            resourceSubscriptionsBySession.getOrPut(sessionId) { linkedSetOf() }.add(resourceUri)
        }
    }

    fun removeResourceSubscription(sessionId: String, resourceUri: String) {
        synchronized(subscriptionLock) {
            resourceSubscriptionsBySession[sessionId]?.let { subs ->
                subs.remove(resourceUri)
                if (subs.isEmpty()) resourceSubscriptionsBySession.remove(sessionId)
            }
        }
    }

    fun subscribedSessionIds(activeSessionIds: Set<String>, resourceUri: String): List<String> {
        return synchronized(subscriptionLock) {
            resourceSubscriptionsBySession.keys.removeAll { it !in activeSessionIds }
            subscriptionHandlerSessionIds.removeAll { it !in activeSessionIds }
            resourceSubscriptionsBySession.filterValues { resourceUri in it }.keys.toList()
        }
    }
}

