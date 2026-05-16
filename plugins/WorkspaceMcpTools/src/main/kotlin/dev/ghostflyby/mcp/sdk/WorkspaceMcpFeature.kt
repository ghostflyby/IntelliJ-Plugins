/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.ExtensionPointName.Companion.create
import dev.ghostflyby.mcp.resource.WorkspaceListableResource
import dev.ghostflyby.mcp.resource.segment.PendingAnchor
import dev.ghostflyby.mcp.resource.segment.ResourceSegmentBuilder
import dev.ghostflyby.mcp.resource.segment.ResourceSegmentCollector
import dev.ghostflyby.mcp.resource.segment.SegmentId
import dev.ghostflyby.mcp.sdk.tools.toolArgsJson
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.serializer

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

    inline fun <reified T : Any> registerTool(
        name: String,
        description: String,
        inputSchema: ToolSchema = ToolSchema(),
        noinline handler: suspend (T, String?) -> CallToolResult,
    ) {
        server.addTool(
            name = name,
            description = description,
            inputSchema = inputSchema,
        ) { request ->
            val jsonArgs: JsonObject = request.params.arguments ?: buildJsonObject { }
            val decoded: T = try {
                toolArgsJson.decodeFromJsonElement(serializer<T>(), jsonArgs)
            } catch (e: SerializationException) {
                return@addTool CallToolResult(
                    content = listOf(TextContent(text = "Invalid arguments for $name: ${e.message}")),
                    isError = true,
                )
            } catch (e: IllegalArgumentException) {
                return@addTool CallToolResult(
                    content = listOf(TextContent(text = "Invalid arguments for $name: ${e.message}")),
                    isError = true,
                )
            }
            handler(decoded, this.sessionId)
        }
        trackedTools.add(name)
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

    fun WorkspaceMcpFeatureRegistrationContext.register(): WorkspaceMcpFeatureRegistration
}
