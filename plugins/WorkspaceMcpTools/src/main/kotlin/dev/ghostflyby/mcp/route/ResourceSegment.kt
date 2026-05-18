/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.route

import io.modelcontextprotocol.kotlin.sdk.types.*

internal typealias ResourceReadHandler = suspend (WorkspaceMcpCall<ReadResourceRequest>) -> ReadResourceResult
internal typealias ResourceListProvider<R, T> = suspend WorkspaceMcpCall<R>.() -> ResourceListDecision<T>

internal typealias ConcreteResourceListProvider = ResourceListProvider<ListResourcesRequest, Resource>
internal typealias TemplateResourceListProvider = ResourceListProvider<ListResourceTemplatesRequest, ResourceTemplate>

internal data class ResourceListDecision<T>(
    val entries: List<T> = emptyList(),
    val includeChildren: Boolean = true,
)

internal data class WorkspaceMcpListProject(
    val projectKey: String,
    val name: String,
    val basePath: String?,
)

internal sealed class ResourceSegment {
    abstract val segmentId: SegmentId
    abstract val name: String
    abstract val extensible: Boolean
    var ownerFeatureName: String? = null
    var resourceEndpoint: ResourceEndpoint? = null
    var templateEndpoint: ResourceTemplateEndpoint? = null

    val children: MutableMap<String, ResourceSegment> = linkedMapOf()
    val anchors: MutableMap<SegmentId, ResourceSegment> = linkedMapOf()
}

internal data class ResourceEndpoint(
    val handler: ResourceReadHandler,
    val listProvider: ConcreteResourceListProvider? = null,
    val description: String = "",
    val mimeType: String = "application/json",
)

internal data class ResourceTemplateEndpoint(
    val listProvider: TemplateResourceListProvider? = null,
    val description: String = "",
    val mimeType: String = "text/plain",
)

internal class LiteralPathSegment(
    override val segmentId: SegmentId,
    override val name: String,
    override val extensible: Boolean = false,
) : ResourceSegment()

internal class ParameterPathSegment(
    override val segmentId: SegmentId,
    override val name: String,
    val paramName: String,
    override val extensible: Boolean = false,
) : ResourceSegment()
