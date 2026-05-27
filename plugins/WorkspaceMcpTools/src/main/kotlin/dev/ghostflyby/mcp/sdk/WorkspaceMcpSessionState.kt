/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.components.Service

@Service(Service.Level.APP)
internal class WorkspaceMcpSessionState {
    private val subscriptionLock = Any()
    private val activeSessionIds = linkedSetOf<String>()
    private val resourceSubscriptionsBySession = linkedMapOf<String, MutableSet<String>>()

    fun recordSessionConnected(sessionId: String) {
        if (sessionId.isBlank()) return
        synchronized(subscriptionLock) {
            activeSessionIds.add(sessionId)
        }
    }

    fun recordSessionClosed(sessionId: String) {
        synchronized(subscriptionLock) {
            activeSessionIds.remove(sessionId)
            resourceSubscriptionsBySession.remove(sessionId)
        }
    }

    fun hasActiveSessions(): Boolean {
        return synchronized(subscriptionLock) {
            activeSessionIds.isNotEmpty()
        }
    }

    fun hasResourceSubscriptions(): Boolean {
        return synchronized(subscriptionLock) {
            resourceSubscriptionsBySession.isNotEmpty()
        }
    }

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

    fun isSubscribed(sessionId: String, resourceUri: String): Boolean {
        return synchronized(subscriptionLock) {
            resourceSubscriptionsBySession[sessionId]?.contains(resourceUri) == true
        }
    }
}
