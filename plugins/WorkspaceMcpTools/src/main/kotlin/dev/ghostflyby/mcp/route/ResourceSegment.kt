/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.route

import com.intellij.openapi.project.Project
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolver
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

// Forwarding extensions so DSL users see project/visibleProjects on McpCallContext directly
internal suspend fun McpCallContext<*>.project(): Project = call.project()
internal suspend fun McpCallContext<*>.project(resolver: WorkspaceProjectResolver): Project = call.project(resolver)
internal suspend fun McpCallContext<*>.visibleProjects(): List<WorkspaceMcpListProject> = call.visibleProjects()
internal suspend fun McpCallContext<*>.visibleProjects(resolver: WorkspaceProjectResolver): List<WorkspaceMcpListProject> = call.visibleProjects(resolver)

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
    var routePattern: RoutePattern? = null
    var routeAnchor: RouteAnchor? = null

    var children: PersistentMap<String, ResourceSegment> = persistentHashMapOf()
    var attachedSegments: PersistentList<ResourceSegment> = persistentListOf()

    /** Read handlers, each optionally bound to query parameters. */
    var readEntries: PersistentList<ReadEntry> = persistentListOf()

    /** Behaviour for resources/list at this segment. Null means no resource listing. */
    var resourceList: ResourceListSpec? = null

    /** Behaviour for resources/templates/list at this segment. Null means no template listing. */
    var templateList: TemplateListSpec? = null
}

// -- Read --

internal data class ReadEntry(
    val handler: ResourceReadHandler,
    val description: String = "",
    val mimeType: String = "application/json",
    val queryTokens: List<QueryToken> = emptyList(),
) {
    val queryTemplate: String
        get() {
            if (queryTokens.isEmpty()) return ""
            return queryTokens.joinToString("&", prefix = "?") { token ->
                when {
                    token.paramName != null -> "${token.key}={${token.paramName}}"
                    token.literalValue != null -> "${token.key}=${token.literalValue}"
                    else -> token.key
                }
            }
        }
}

// -- List specs --

internal data class ResourceListSpec(
    val listProvider: ConcreteResourceListProvider? = null,
    val description: String = "",
    val mimeType: String = "application/json",
)

internal data class TemplateListSpec(
    val listProvider: TemplateResourceListProvider? = null,
    val description: String = "",
    val mimeType: String = "text/plain",
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
