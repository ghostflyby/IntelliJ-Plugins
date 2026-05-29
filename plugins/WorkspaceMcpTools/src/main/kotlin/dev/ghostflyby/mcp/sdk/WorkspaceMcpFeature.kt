/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.ExtensionPointName.Companion.create
import dev.ghostflyby.mcp.route.*
import dev.ghostflyby.mcp.sdk.tools.reflectTools
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

internal val WORKSPACE_MCP_FEATURE_EP: ExtensionPointName<WorkspaceMcpFeature> =
    create("dev.ghostflyby.mcp.workspace.workspaceFeature")

/**
 * Registration context provided to [WorkspaceMcpFeature.register].
 * Features use this to register resource templates and SDK tools on the server.
 * Tracks registered resources/tools for cleanup on dynamic removal.
 */
internal class WorkspaceMcpFeatureRegistrationContext(
    val projectResolver: WorkspaceProjectProvider,
    val server: Server,
    val featureScope: CoroutineScope,
    val featureName: String,
    val invalidationSink: WorkspaceMcpInvalidationSink,
    private val callFactory: WorkspaceMcpCallFactory = workspaceMcpCallFactory(projectResolver),
) {
    private val trackedTools = mutableSetOf<String>()
    internal val segmentCollector: ResourceSegmentCollector = ResourceSegmentCollector()

    /**
     * Register a tool class via reflection.
     *
     * Discovers `suspend fun McpCallContext<CallToolRequest>.name(args: @Serializable P): R`
     * functions and registers each as an MCP tool. Input schema is obtained
     * from the KSP-generated `KClass<P>.jsonSchema` convention.
     */
    inline fun <reified T : Any> registerToolClass() {
        for ((tool, handler) in reflectTools(T::class, callFactory)) {
            server.addTool(tool, handler)
            trackedTools.add(tool.name)
        }
    }

    inline fun <reified T : Any> read(noinline handler: suspend McpCallContext<ReadResourceRequest>.(T) -> ReadResourceResult) {
        segmentCollector.read(handler)
    }

    inline fun <reified T : Any> listResources(
        noinline listProvider: (suspend McpCallContext<ListResourcesRequest>.() -> List<Resource>),
    ) {
        segmentCollector.listResources<T>(listProvider)
    }

    inline fun <reified T : Any> listTemplates(
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
 * Used for cleanup on dynamic removal.
 */
internal data class WorkspaceMcpFeatureRegistration(
    val featureName: String,
    val job: Job,
    val registeredTools: Set<String>,
    val roots: List<ResourceSegment> = emptyList(),
    val resourceListRoutes: List<ResourceListRoute> = emptyList(),
    val templateListRoutes: List<ResourceTemplateListRoute> = emptyList(),
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
internal interface WorkspaceMcpFeature {
    val featureName: String

    fun WorkspaceMcpFeatureRegistrationContext.register()
}
