/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

internal interface WorkspaceMcpInvalidationSink {
    fun invalidateResource(uri: String)

    fun invalidateResourceList(selector: ResourceListSelector = ResourceListSelector.AllSessions)

    fun invalidateToolList()
}

internal sealed interface ResourceListSelector {
    data object AllSessions : ResourceListSelector

    data class Session(val sessionId: String) : ResourceListSelector

    data class Uri(val uri: String) : ResourceListSelector

    data class UriPrefix(val uriPrefix: String) : ResourceListSelector
}
