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

internal sealed interface WorkspaceMcpInvalidation {
    data class Resource(val uri: String) : WorkspaceMcpInvalidation

    data class ResourceList(val selector: ResourceListSelector) : WorkspaceMcpInvalidation

    data object ToolList : WorkspaceMcpInvalidation
}

internal data class CoalescedInvalidations(
    val resourceUris: Set<String> = emptySet(),
    val resourceListSelectors: Set<ResourceListSelector> = emptySet(),
    val toolListChanged: Boolean = false,
) {
    val isEmpty: Boolean
        get() = resourceUris.isEmpty() && resourceListSelectors.isEmpty() && !toolListChanged

    fun plus(event: WorkspaceMcpInvalidation): CoalescedInvalidations {
        return when (event) {
            is WorkspaceMcpInvalidation.Resource -> copy(resourceUris = resourceUris + event.uri)
            is WorkspaceMcpInvalidation.ResourceList -> copy(resourceListSelectors = resourceListSelectors + event.selector)
            WorkspaceMcpInvalidation.ToolList -> copy(toolListChanged = true)
        }
    }
}

internal sealed interface ResourceListSelector {
    data object AllSessions : ResourceListSelector

    data class Session(val sessionId: String) : ResourceListSelector

    data class Uri(val uri: String) : ResourceListSelector

    data class UriPrefix(val uriPrefix: String) : ResourceListSelector
}
