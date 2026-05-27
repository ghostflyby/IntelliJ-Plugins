/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal class WorkspaceMcpInvalidationBus(
    private val scope: CoroutineScope,
    private val coalesceWindow: Duration = 100.milliseconds,
) : WorkspaceMcpInvalidationSink {
    private val _resourceUpdates = MutableStateFlow<Set<String>>(emptySet())
    private val _listChanged = MutableStateFlow<Set<ListChangedEvent>>(emptySet())

    val resourceUpdateBatches: StateFlow<Set<String>> = _resourceUpdates
    val listChangedBatches: StateFlow<Set<ListChangedEvent>> = _listChanged

    init {
        @OptIn(FlowPreview::class)
        scope.launch {
            _resourceUpdates
                .debounce(coalesceWindow)
                .collect { batch ->
                    if (batch.isNotEmpty()) {
                        _resourceUpdates.compareAndSet(batch, emptySet())
                    }
                }
        }
        @OptIn(FlowPreview::class)
        scope.launch {
            _listChanged
                .debounce(coalesceWindow)
                .collect { batch ->
                    if (batch.isNotEmpty()) {
                        _listChanged.compareAndSet(batch, emptySet())
                    }
                }
        }
    }

    override fun <T> registerResourceUpdates(flow: StateFlow<T>, uris: (T) -> Set<String>) {
        scope.launch {
            flow.drop(1).collect { state ->
                val validUris = uris(state).filterTo(linkedSetOf()) {
                    WorkspaceMcpResourceSubscriptionService.isWorkspaceResourceUri(it)
                }
                if (validUris.isNotEmpty()) {
                    _resourceUpdates.update { it + validUris }
                }
            }
        }
    }

    override fun <T> registerGlobalListChanged(kind: ListChangeKind, flow: StateFlow<T>) {
        scope.launch {
            flow.drop(1).collect {
                _listChanged.update { it + ListChangedEvent(kind, SessionSelector.AllSessions) }
            }
        }
    }

    override fun <T> registerSessionListChanged(
        kind: ListChangeKind,
        flow: StateFlow<T>,
        sessions: (T) -> Set<String>,
    ) {
        scope.launch {
            flow.drop(1).collect { state ->
                val sessionIds = sessions(state).filterTo(linkedSetOf()) { it.isNotBlank() }
                if (sessionIds.isNotEmpty()) {
                    _listChanged.update { it + ListChangedEvent(kind, SessionSelector.Sessions(sessionIds)) }
                }
            }
        }
    }
}
