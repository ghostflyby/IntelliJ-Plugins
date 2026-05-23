/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.route

import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.persistentListOf

internal typealias ResourceReadHandler = suspend McpCallContext<ReadResourceRequest>.() -> ReadResourceResult
internal typealias ResourceListProvider<R, T> = suspend McpCallContext<R>.() -> ResourceListDecision<T>

internal typealias ConcreteResourceListProvider = ResourceListProvider<ListResourcesRequest, Resource>
internal typealias TemplateResourceListProvider = ResourceListProvider<ListResourceTemplatesRequest, ResourceTemplate>

@JvmInline
internal value class McpCallContext<R : Request>(val call: WorkspaceMcpCall<R>)

internal data class ResourceListDecision<T>(
    val entries: List<T> = emptyList(),
    val includeChildren: Boolean = true,
)

internal data class WorkspaceMcpListProject(
    val projectKey: String,
    val name: String,
    val basePath: String?,
)

// -- Segment tree node --

internal sealed class ResourceSegment {
    abstract val name: String
    abstract val extensible: Boolean
    var ownerFeatureName: String? = null

    var children: PersistentMap<String, ResourceSegment> = persistentHashMapOf()

    /** Read handlers, each optionally bound to query parameters. */
    var readEntries: PersistentList<ReadEntry> = persistentListOf()

    /** Behaviour for resources/list at this segment. Null means no resource listing. */
    var resourceList: ResourceListSpec? = null

    /** Behaviour for resources/templates/list at this segment. Null means no template listing. */
    var templateList: TemplateListSpec? = null
}

// -- Read --

internal data class ReadEntry(
    val description: String = "",
    val mimeType: String = "application/json",
    val resourceClassInfo: ResourceClassInfo? = null,
    val invoker: suspend (McpCallContext<ReadResourceRequest>, Any?) -> ReadResourceResult,
    val paramDeserializer: ((Map<String, String>) -> Any?)? = null,
)

// -- List specs --

internal data class ResourceListSpec(
    val description: String = "",
    val mimeType: String = "application/json",
    val resourceClassInfo: ResourceClassInfo? = null,
    val invoker: (suspend (McpCallContext<ListResourcesRequest>, Any?) -> ResourceListDecision<Resource>)? = null,
)

internal data class TemplateListSpec(
    val description: String = "",
    val mimeType: String = "text/plain",
    val resourceClassInfo: ResourceClassInfo? = null,
    val invoker: (suspend (McpCallContext<ListResourceTemplatesRequest>, Any?) -> ResourceListDecision<ResourceTemplate>)? = null,
)

// -- Segment subtypes --

internal class LiteralPathSegment(
    override val name: String,
    override val extensible: Boolean = false,
) : ResourceSegment()

internal class ParameterPathSegment(
    override val name: String,
    val paramName: String,
    override val extensible: Boolean = false,
) : ResourceSegment()
