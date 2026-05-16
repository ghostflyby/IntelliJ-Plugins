/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * This file is part of IntelliJ-Plugins by ghostflyby
 *
 * IntelliJ-Plugins by ghostflyby is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see
 * <https://www.gnu.org/licenses/>.
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.ExtensionPointName.Companion.create
import dev.ghostflyby.mcp.resource.WorkspaceListableResource
import dev.ghostflyby.mcp.resource.segment.PendingAnchor
import dev.ghostflyby.mcp.resource.segment.ResourceSegmentBuilder
import dev.ghostflyby.mcp.resource.segment.ResourceSegmentCollector
import dev.ghostflyby.mcp.resource.segment.SegmentId
import dev.ghostflyby.mcp.sdk.tools.SdkToolDescriptor
import dev.ghostflyby.mcp.sdk.tools.registerSdkTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

internal val WORKSPACE_MCP_FEATURE_EP: ExtensionPointName<WorkspaceMcpFeature> =
    create("dev.ghostflyby.mcp.workspace.workspaceFeature")

/**
 * Context provided to [WorkspaceMcpFeature.computeListableResources].
 */
internal class WorkspaceMcpFeatureContext(
    val projectResolver: WorkspaceProjectProvider,
    val readResource: suspend (resourceUri: String, sessionId: String?) -> ReadResourceResult,
)

/**
 * Registration context provided to [WorkspaceMcpFeature.register].
 * Features use this to register resource templates and SDK tools on the server.
 * Tracks registered resources/tools for cleanup on dynamic removal.
 */
internal class WorkspaceMcpFeatureRegistrationContext(
    val projectResolver: WorkspaceProjectResolver,
    val requestRunner: WorkspaceMcpRequestRunner,
    val server: Server,
    val featureScope: CoroutineScope,
    val featureName: String,
    internal val readResource: suspend (resourceUri: String, sessionId: String?) -> ReadResourceResult,
) {
    private val trackedTemplates = mutableSetOf<String>()
    private val trackedTools = mutableSetOf<String>()
    internal val segmentCollector: ResourceSegmentCollector = ResourceSegmentCollector()

    fun registerResourceTemplate(
        uriTemplate: String,
        name: String,
        description: String,
        mimeType: String,
    ) {
        server.addResourceTemplate(
            uriTemplate = uriTemplate,
            name = name,
            description = description,
            mimeType = mimeType,
        ) { request, _ ->
            readResource(request.uri, this.sessionId)
        }
        trackedTemplates.add(uriTemplate)
    }

    fun registerTool(descriptor: SdkToolDescriptor<*>) {
        @Suppress("UNCHECKED_CAST")
        server.registerSdkTool(descriptor as SdkToolDescriptor<Any>, requestRunner)
        trackedTools.add(descriptor.name)
    }

    /**
     * Register resource segments using the builder DSL.
     * Segments are collected via [segmentCollector] and later assembled
     * into the global resource tree by the server service.
     */
    fun segments(block: ResourceSegmentBuilder.() -> Unit) {
        segmentCollector.block()
    }

    fun buildRegistration(): WorkspaceMcpFeatureRegistration {
        // Resolve deferred under() anchors within this feature's own collector.
        val pendingAnchors = segmentCollector.pendingAnchors.toList()
        val segmentIds = segmentCollector.roots.map { it.segmentId }.toSet()
        return WorkspaceMcpFeatureRegistration(
            featureName = featureName,
            job = featureScope.coroutineContext[Job] ?: Job(),
            registeredTemplates = trackedTemplates.toSet(),
            registeredTools = trackedTools.toSet(),
            segmentIds = segmentIds,
            pendingAnchors = pendingAnchors,
        )
    }
}

/**
 * Record of what a [WorkspaceMcpFeature] registered on the server.
 * Used for cleanup on dynamic removal.
 */
internal data class WorkspaceMcpFeatureRegistration(
    val featureName: String,
    val job: Job,
    val registeredTemplates: Set<String>,
    val registeredTools: Set<String>,
    val segmentIds: Set<SegmentId> = emptySet(),
    val pendingAnchors: List<PendingAnchor> = emptyList(),
)

/**
 * SDK-neutral feature registration boundary for the Workspace MCP SDK server.
 *
 * Each [WorkspaceMcpFeature] owns a domain (e.g. core metadata, VFS resources,
 * document resources) and is responsible for:
 * - computing its own listable resources on demand (using [WorkspaceMcpFeatureContext]),
 * - registering resource templates and SDK tools during server startup
 *   (using [WorkspaceMcpFeatureRegistrationContext]).
 *
 * The server service aggregates features and delegates resource listing,
 * template registration, and tool registration to them.
 */
internal interface WorkspaceMcpFeature {
    val featureName: String

    suspend fun computeListableResources(context: WorkspaceMcpFeatureContext): List<WorkspaceListableResource>

    fun register(context: WorkspaceMcpFeatureRegistrationContext): WorkspaceMcpFeatureRegistration
}
