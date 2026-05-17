/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.ExtensionPointName.Companion.create
import dev.ghostflyby.mcp.resource.segment.*
import dev.ghostflyby.mcp.sdk.tools.toolArgsJson
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Request
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

internal val WORKSPACE_MCP_FEATURE_EP: ExtensionPointName<WorkspaceMcpFeature> =
    create("dev.ghostflyby.mcp.workspace.workspaceFeature")

/**
 * Registration context provided to [WorkspaceMcpFeature.register].
 * Features use this to register resource templates and SDK tools on the server.
 * Tracks registered resources/tools for cleanup on dynamic removal.
 */
internal class WorkspaceMcpFeatureRegistrationContext(
    val projectResolver: WorkspaceProjectResolver,
    val server: Server,
    val featureScope: CoroutineScope,
    val featureName: String,
) {
    private val trackedTools = mutableSetOf<String>()
    internal val segmentCollector: ResourceSegmentCollector = ResourceSegmentCollector()

    inline fun <reified T : Any> registerTool(
        name: String,
        description: String,
        schema: JsonObject = JsonObject(emptyMap()),
        noinline handler: suspend (T, Request) -> CallToolResult,
    ) {
        server.addTool(
            name = name,
            description = description,
            inputSchema = ToolSchema(
                properties = schema["properties"] as? JsonObject,
                required = (schema["required"] as? JsonArray)?.map { it.jsonPrimitive.content },
            ),
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
            handler(decoded, request)
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
            registeredTools = trackedTools.toSet(),
            segmentIds = segmentIds,
            pendingAnchors = pendingAnchors,
            roots = segmentCollector.roots.toList(),
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
    val registeredTools: Set<String>,
    val segmentIds: Set<SegmentId> = emptySet(),
    val pendingAnchors: List<PendingAnchor> = emptyList(),
    val roots: List<ResourceSegment> = emptyList(),
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

    fun WorkspaceMcpFeatureRegistrationContext.register(): WorkspaceMcpFeatureRegistration
}

/**
 * Call [WorkspaceMcpFeature]'s extension-function register via a standalone
 * bridge that Kotlin can dispatch through.
 */
