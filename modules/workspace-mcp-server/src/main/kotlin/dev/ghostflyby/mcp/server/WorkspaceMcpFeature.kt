/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.server

import dev.ghostflyby.mcp.server.route.*
import dev.ghostflyby.mcp.server.tools.reflectTools
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Registration context provided to [WorkspaceMcpFeature.register].
 * Features use this to register resource templates and SDK tools on the server.
 * Tracks registered resources/tools for cleanup on dynamic removal.
 */
public class WorkspaceMcpFeatureRegistrationContext(
    public val server: Server,
    public val featureScope: CoroutineScope,
    public val featureName: String,
    public val invalidationSink: WorkspaceMcpInvalidationSink,
    public val callFactory: McpCallFactory = mcpCallFactory(),
) {
    public val trackedTools: MutableSet<String> = mutableSetOf()
    public val segmentCollector: ResourceSegmentCollector = ResourceSegmentCollector()

    /**
     * Register a tool class via reflection.
     *
     * Discovers `suspend fun McpCallContext<CallToolRequest>.name(args: @Serializable P): R`
     * functions and registers each as an MCP tool. Input schema is obtained
     * from the KSP-generated `KClass<P>.jsonSchema` convention.
     */
    public inline fun <reified T : Any> registerToolClass() {
        for ((tool, handler) in reflectTools(T::class, callFactory)) {
            server.addTool(tool, handler)
            trackedTools.add(tool.name)
        }
    }

    public inline fun <reified T : Any> read(noinline handler: suspend McpCallContext<ReadResourceRequest>.(T) -> ReadResourceResult) {
        segmentCollector.read(handler)
    }

    public inline fun <reified T : Any> listResources(
        noinline listProvider: (suspend McpCallContext<ListResourcesRequest>.() -> List<Resource>),
    ) {
        segmentCollector.listResources<T>(listProvider)
    }

    public inline fun <reified T : Any> listTemplates(
        noinline listProvider: (suspend McpCallContext<ListResourceTemplatesRequest>.() -> List<ResourceTemplate>),
    ) {
        segmentCollector.listTemplates<T>(listProvider)
    }

    internal fun buildRegistration(): WorkspaceMcpFeatureRegistration {
        segmentCollector.roots.forEach { it.markOwner(featureName) }
        return WorkspaceMcpFeatureRegistration(
            featureName = featureName,
            job = featureScope.coroutineContext[Job] ?: Job(),
            registeredTools = trackedTools.toSet(),
            roots = segmentCollector.roots.toList(),
            resourceListRoutes = segmentCollector.resourceListRoutes.toList(),
            templateListRoutes = segmentCollector.templateListRoutes.toList(),
        )
    }
}

private fun ResourceSegment.markOwner(featureName: String) {
    ownerFeatureName = featureName
    children.values.forEach { it.markOwner(featureName) }
}

/**
 * Record of what a [WorkspaceMcpFeature] registered on the server.
 * Used for clean-up on dynamic removal.
 */
public data class WorkspaceMcpFeatureRegistration(
    public val featureName: String,
    public val job: Job,
    public val registeredTools: Set<String>,
    public val roots: List<ResourceSegment> = emptyList(),
    public val resourceListRoutes: List<ResourceListRoute> = emptyList(),
    public val templateListRoutes: List<ResourceTemplateListRoute> = emptyList(),
)

/**
 * SDK-neutral feature registration boundary for the Workspace MCP SDK server.
 *
 * Each [WorkspaceMcpFeature] owns a domain (e.g. core metadata, VFS resources,
 * document resources) and is responsible for:
 * - registering resource templates and SDK tools during server startup
 *   (using [WorkspaceMcpFeatureRegistrationContext]).
 *
 * The server service aggregates features and delegates resource listing,
 * template registration, and tool registration to them.
 */
public interface WorkspaceMcpFeature {
    public val featureName: String

    public fun WorkspaceMcpFeatureRegistrationContext.register()
}
