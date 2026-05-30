/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.server

public class WorkspaceMcpSessionState {
    private val subscriptionLock = Any()
    private val activeSessionIds = linkedSetOf<String>()
    private val resourceSubscriptionsBySession = linkedMapOf<String, MutableSet<String>>()

    public fun recordSessionConnected(sessionId: String) {
        if (sessionId.isBlank()) return
        synchronized(subscriptionLock) {
            activeSessionIds.add(sessionId)
        }
    }

    public fun recordSessionClosed(sessionId: String) {
        synchronized(subscriptionLock) {
            activeSessionIds.remove(sessionId)
            resourceSubscriptionsBySession.remove(sessionId)
        }
    }

    public fun hasActiveSessions(): Boolean {
        return synchronized(subscriptionLock) {
            activeSessionIds.isNotEmpty()
        }
    }

    public fun hasResourceSubscriptions(): Boolean {
        return synchronized(subscriptionLock) {
            resourceSubscriptionsBySession.isNotEmpty()
        }
    }

    public fun recordResourceSubscription(sessionId: String, resourceUri: String) {
        synchronized(subscriptionLock) {
            resourceSubscriptionsBySession.getOrPut(sessionId) { linkedSetOf() }.add(resourceUri)
        }
    }

    public fun removeResourceSubscription(sessionId: String, resourceUri: String) {
        synchronized(subscriptionLock) {
            resourceSubscriptionsBySession[sessionId]?.let { subs ->
                subs.remove(resourceUri)
                if (subs.isEmpty()) resourceSubscriptionsBySession.remove(sessionId)
            }
        }
    }

    public fun isSubscribed(sessionId: String, resourceUri: String): Boolean {
        return synchronized(subscriptionLock) {
            resourceSubscriptionsBySession[sessionId]?.contains(resourceUri) == true
        }
    }
}
