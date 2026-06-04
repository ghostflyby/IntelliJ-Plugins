/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.ExtensionPointListener
import com.intellij.openapi.extensions.PluginDescriptor
import dev.ghostflyby.mcp.pluginVersion
import dev.ghostflyby.mcp.rest.restApi
import dev.ghostflyby.mcp.server.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.resources.*
import io.ktor.server.routing.*
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Service(Service.Level.APP)
internal class WorkspaceMcpSdkServerService(
    private val scope: CoroutineScope,
) {
    private val logger = logger<WorkspaceMcpSdkServerService>()

    private val projectResolver = service<WorkspaceProjectResolver>()
    private val sessionState = service<WorkspaceMcpSessionStateService>()
    private val stateFlows = service<WorkspaceMcpStateFlows>()

    private val features: List<WorkspaceMcpFeature>
        get() = WORKSPACE_MCP_FEATURE_EP.extensionList

    @Volatile
    private var core: WorkspaceMcpServerCore? = null

    init {
        subscribeToFeatureEvents()
        if (!ApplicationManager.getApplication().isUnitTestMode) {
            scope.launch {
                val createdCore = createCore(features)
                try {
                    val port = service<WorkspaceMcpSdkServerSettings>().port
                    core = createdCore
                    embeddedServer(CIO, host = LOOPBACK_HOST, port = port) {
                        install(Resources)
                        mcpStreamableHttp(path = MCP_ENDPOINT_PATH) { createdCore.server }
                        routing { restApi() }
                    }.startSuspend(wait = true)
                    @Suppress("HttpUrlsUsage")
                    logger.info(
                        "Workspace MCP server started at " +
                                "http://$LOOPBACK_HOST:$port$MCP_ENDPOINT_PATH (MCP) " +
                                "http://$LOOPBACK_HOST:$port/api/v1 (REST)",
                    )
                } finally {
                    core = null
                    runCatching { createdCore.close() }
                        .onFailure { error -> logger.warn("Failed to close Workspace MCP SDK server", error) }
                }
            }
        }
    }

    private fun createCore(initialFeatures: List<WorkspaceMcpFeature>): WorkspaceMcpServerCore {
        val callFactory = mcpCallFactory().withAttributes {
            attributes[SdkKeys.ProjectProvider] = projectResolver
            attributes[SdkKeys.InstanceKey] = workspaceInstanceKey()
        }
        return WorkspaceMcpServerCore(
            parentScope = scope,
            serverInfo = Implementation(name = "workspace-mcp", version = pluginVersion),
            initialFeatures = initialFeatures,
            stateFlows = stateFlows,
            sessionState = sessionState.state,
            callFactory = callFactory,
            instanceKeyProvider = ::workspaceInstanceKey,
            instructions = "Workspace MCP exposes IntelliJ VFS and editor document snapshots as MCP resources.",
            logger = object : WorkspaceMcpCoreLogger {
                override fun warn(message: String, error: Throwable?) {
                    if (error == null) logger.warn(message) else logger.warn(message, error)
                }
            },
        )
    }

    private fun subscribeToFeatureEvents() {
        WORKSPACE_MCP_FEATURE_EP.point.addExtensionPointListener(
            scope,
            false,
            object : ExtensionPointListener<WorkspaceMcpFeature> {
                override fun extensionAdded(extension: WorkspaceMcpFeature, pluginDescriptor: PluginDescriptor) {
                    scope.launch {
                        core?.register(extension)
                    }
                }

                override fun extensionRemoved(extension: WorkspaceMcpFeature, pluginDescriptor: PluginDescriptor) {
                    scope.launch {
                        core?.unregister(extension.featureName)
                    }
                }
            },
        )
    }

    private companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val MCP_ENDPOINT_PATH = "/mcp"
    }
}
