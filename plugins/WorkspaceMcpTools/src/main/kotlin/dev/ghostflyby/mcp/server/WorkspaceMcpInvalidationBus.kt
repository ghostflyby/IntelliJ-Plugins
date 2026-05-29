/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.server

import dev.ghostflyby.mcp.sdk.PerSessionListChange
import dev.ghostflyby.mcp.sdk.ResourceUpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal class WorkspaceMcpInvalidationBus(
    private val scope: CoroutineScope,
    private val coalesceWindow: Duration = 100.milliseconds,
) : WorkspaceMcpInvalidationSink {
    private val _resourceUpdates = MutableStateFlow<Set<String>>(emptySet())
    private val _listChanged = MutableStateFlow<Set<ListChangedEvent>>(emptySet())

    val resourceUpdateBatches: StateFlow<Set<String>> = _resourceUpdates.asStateFlow()
    val listChangedBatches: StateFlow<Set<ListChangedEvent>> = _listChanged.asStateFlow()

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

    fun registerResourceUpdates(flow: StateFlow<ResourceUpdateState>) {
        scope.launch {
            var seenGenerations = flow.value.uriGenerations
            flow.drop(1).collect { state ->
                val changedUris = state.uriGenerations.asSequence()
                    .filter { (uri, generation) ->
                        generation != seenGenerations[uri] &&
                                WorkspaceMcpResourceSubscriptionService.isWorkspaceResourceUri(uri)
                    }
                    .map { (uri, _) -> uri }
                    .toSet()
                seenGenerations = state.uriGenerations
                if (changedUris.isNotEmpty()) {
                    _resourceUpdates.update { it + changedUris }
                }
            }
        }
    }

    fun registerSessionListChanged(kind: ListChangeKind, flow: StateFlow<PerSessionListChange>) {
        scope.launch {
            var seenGenerations = flow.value.sessionGenerations
            flow.drop(1).collect { state ->
                val sessionIds = state.sessionGenerations.asSequence()
                    .filter { (sessionId, generation) ->
                        sessionId.isNotBlank() && generation != seenGenerations[sessionId]
                    }
                    .map { (sessionId, _) -> sessionId }
                    .toSet()
                seenGenerations = state.sessionGenerations
                if (sessionIds.isNotEmpty()) {
                    _listChanged.update { it + ListChangedEvent(kind, SessionSelector.Sessions(sessionIds)) }
                }
            }
        }
    }
}
