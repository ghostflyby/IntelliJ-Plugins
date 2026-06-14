/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.server

import kotlinx.coroutines.flow.StateFlow

public enum class ListChangeKind { Resources, Tools }

public interface WorkspaceMcpInvalidationSink {
    /** URI content changes -> [resources/updated]. */
    public fun <T> registerResourceUpdates(flow: StateFlow<T>, uris: (T) -> Set<String>)

    /** Any emission triggers global [kind] list_changed for all sessions. */
    public fun <T> registerGlobalListChanged(kind: ListChangeKind, flow: StateFlow<T>)

    /** Each emission carries the affected session IDs. */
    public fun <T> registerSessionListChanged(kind: ListChangeKind, flow: StateFlow<T>, sessions: (T) -> Set<String>)
}

public data class ListChangedEvent(val kind: ListChangeKind, val selector: SessionSelector)

public sealed interface SessionSelector {
    public data object AllSessions : SessionSelector

    public data class Sessions(val sessionIds: Set<String>) : SessionSelector
}
