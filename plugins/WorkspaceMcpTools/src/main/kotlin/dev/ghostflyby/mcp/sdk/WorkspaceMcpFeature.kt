/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.ExtensionPointName.Companion.create
import dev.ghostflyby.mcp.resource.WorkspaceListableResource
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
    val projectResolver: WorkspaceProjectProvider,
    val requestRunner: WorkspaceMcpRequestRunner,
    val server: Server,
    val featureScope: CoroutineScope,
    val featureName: String,
    private val readResource: suspend (resourceUri: String, sessionId: String?) -> ReadResourceResult,
) {
    private val trackedTemplates = mutableSetOf<String>()
    private val trackedTools = mutableSetOf<String>()

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

    fun buildRegistration(): WorkspaceMcpFeatureRegistration = WorkspaceMcpFeatureRegistration(
        featureName = featureName,
        job = featureScope.coroutineContext[Job] ?: Job(),
        registeredTemplates = trackedTemplates.toSet(),
        registeredTools = trackedTools.toSet(),
    )
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
