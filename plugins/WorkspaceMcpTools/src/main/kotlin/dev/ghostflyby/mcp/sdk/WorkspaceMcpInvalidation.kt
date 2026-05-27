/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import kotlinx.coroutines.flow.StateFlow

internal enum class ListChangeKind { Resources, Tools }

internal interface WorkspaceMcpInvalidationSink {
    /** URI content changes -> [resources/updated]. */
    fun <T> registerResourceUpdates(flow: StateFlow<T>, uris: (T) -> Set<String>)

    /** Any emission triggers global [kind] list_changed for all sessions. */
    fun <T> registerGlobalListChanged(kind: ListChangeKind, flow: StateFlow<T>)

    /** Each emission carries the affected session IDs. */
    fun <T> registerSessionListChanged(kind: ListChangeKind, flow: StateFlow<T>, sessions: (T) -> Set<String>)
}

internal data class ListChangedEvent(val kind: ListChangeKind, val selector: SessionSelector)

internal sealed interface SessionSelector {
    data object AllSessions : SessionSelector

    data class Sessions(val sessionIds: Set<String>) : SessionSelector
}
