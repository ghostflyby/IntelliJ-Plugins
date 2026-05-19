/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.diagnostic.logger
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

/**
 * Session-scoped dispatch for resource list changed notifications.
 *
 * Coalesces repeated notifications into a single flush per cycle,
 * and supports both session-scoped (roots change) and global
 * (feature add/remove) notification patterns.
 */
internal class WorkspaceMcpResourceEventBus(
    private val scope: CoroutineScope,
    private val serverProvider: () -> Server?,
) {
    private val logger = logger<WorkspaceMcpResourceEventBus>()
    private val pendingRef = AtomicReference<Set<String>>(emptySet())
    private var flushJob: Job? = null

    /**
     * Schedule a resource list changed notification for a single session.
     */
    fun scheduleSessionListChanged(sessionId: String) {
        while (true) {
            val current = pendingRef.get()
            if (pendingRef.compareAndSet(current, current + sessionId)) break
        }
        scheduleFlush()
    }

    /**
     * Schedule a resource list changed notification for all sessions.
     */
    fun scheduleAllSessionsListChanged() {
        val activeServer = serverProvider()
        if (activeServer != null) {
            val keys = activeServer.sessions.keys
            while (true) {
                val current = pendingRef.get()
                if (pendingRef.compareAndSet(current, current + keys)) break
            }
        }
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (flushJob != null) return
        flushJob = scope.launch {
            delay(COALESCE_MILLIS.milliseconds)
            flush()
        }
    }

    private suspend fun flush() {
        val targetSessionIds: Set<String> = pendingRef.getAndSet(emptySet())
        flushJob = null
        val activeServer = serverProvider() ?: return
        val aliveIds = activeServer.sessions.keys
        targetSessionIds.intersect(aliveIds).forEach { sessionId ->
            runCatching { activeServer.sendResourceListChanged(sessionId) }
                .onFailure { error ->
                    logger.warn(
                        "Failed to send resource list changed to session $sessionId",
                        error,
                    )
                }
        }
    }

    private companion object {
        private const val COALESCE_MILLIS = 100L
    }
}

