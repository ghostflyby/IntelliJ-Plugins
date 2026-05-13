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

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.ProjectManager
import dev.ghostflyby.mcp.resource.WorkspaceDocumentResourceReadOptions
import dev.ghostflyby.mcp.resource.WorkspaceListableResource
import dev.ghostflyby.mcp.resource.WorkspaceResourceCatalog
import dev.ghostflyby.mcp.resource.WorkspaceResourceException
import dev.ghostflyby.mcp.resource.WorkspaceResourceReader
import dev.ghostflyby.mcp.resource.WorkspaceVfsResourceReadOptions
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Service(Service.Level.APP)
internal class WorkspaceMcpSdkServerService(
    private val scope: CoroutineScope,
) : Disposable {
    private val logger = logger<WorkspaceMcpSdkServerService>()
    private val resourceReader = WorkspaceResourceReader()

    @Volatile
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @Volatile
    private var server: Server? = null

    init {
        scope.launch {
            runCatching {
                val listableResources = listWorkspaceResources()
                val createdServer = createServer(listableResources)
                val started = embeddedServer(CIO, host = LOOPBACK_HOST, port = 0) {
                    mcpStreamableHttp(path = MCP_ENDPOINT_PATH) {
                        createdServer
                    }
                }
                    .start(wait = false)
                server = createdServer
                engine = started
                logger.info("Workspace MCP SDK server started at http://$LOOPBACK_HOST:<assigned>$MCP_ENDPOINT_PATH")
            }.onFailure { error ->
                logger.warn("Failed to start Workspace MCP SDK server", error)
            }
        }
    }

    override fun dispose() {
        scope.launch {
            closeServer()
        }
    }

    private suspend fun closeServer() {
        val stoppedEngine = engine
        val stoppedServer = server
        engine = null
        server = null
        runCatching {
            stoppedServer?.close()
        }.onFailure { error ->
            logger.warn("Failed to close Workspace MCP SDK server", error)
        }
        stoppedEngine?.stop()
    }

    private suspend fun listWorkspaceResources(): List<WorkspaceListableResource> {
        return ProjectManager.getInstance().openProjects
            .flatMap { project -> WorkspaceResourceCatalog(project).listResources() }
            .distinctBy { it.uri }
    }

    private fun createServer(listableResources: List<WorkspaceListableResource>): Server {
        return Server(
            serverInfo = Implementation(
                name = "workspace-mcp",
                version = "1.0.0",
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    resources = ServerCapabilities.Resources(
                        subscribe = true,
                        listChanged = true,
                    ),
                    tools = ServerCapabilities.Tools(
                        listChanged = true,
                    ),
                ),
                resourceTemplateMatcherFactory = WorkspaceResourceTemplateMatcherFactory,
            ),
            instructions = "Workspace MCP exposes IntelliJ VFS and editor document snapshots as MCP resources.",
        ) {
            registerListableWorkspaceResources(listableResources)
            registerWorkspaceResourceTemplates()
        }
    }

    private fun Server.registerListableWorkspaceResources(listableResources: List<WorkspaceListableResource>) {
        listableResources.forEach { entry ->
            addResource(
                uri = entry.uri,
                name = entry.name,
                description = entry.description,
                mimeType = entry.mimeType,
            ) {
                resourceReader.readWorkspaceResource(entry.uri).toReadResourceResult()
            }
        }
    }

    private fun Server.registerWorkspaceResourceTemplates() {
        addResourceTemplate(
            uriTemplate = "ij-workspace-vfs://{rawVfsUrl}",
            name = "IntelliJ VFS resource",
            description = "Reads IntelliJ VirtualFile content, directory listing, metadata, or API signature snapshots.",
            mimeType = "text/plain",
        ) { request, variables ->
            val rawVfsUrl = variables.requireRawVfsUrl()
            val content = resourceReader.readVfsResource(
                resourceUri = request.uri,
                rawVfsUrl = rawVfsUrl,
                options = WorkspaceVfsResourceReadOptions(),
            )
            content.toReadResourceResult()
        }

        addResourceTemplate(
            uriTemplate = "ij-workspace-document://{rawVfsUrl}",
            name = "IntelliJ editor document resource",
            description = "Reads the current IntelliJ editor document snapshot for a VFS URL, including unsaved text.",
            mimeType = "text/plain",
        ) { request, variables ->
            val rawVfsUrl = variables.requireRawVfsUrl()
            val content = resourceReader.readDocumentResource(
                resourceUri = request.uri,
                rawVfsUrl = rawVfsUrl,
                options = WorkspaceDocumentResourceReadOptions(),
            )
            content.toReadResourceResult()
        }
    }

    private fun Map<String, String>.requireRawVfsUrl(): String {
        return this["rawVfsUrl"]?.takeIf { it.isNotBlank() }
            ?: throw WorkspaceResourceException("Resource template did not provide rawVfsUrl.")
    }

    private fun dev.ghostflyby.mcp.resource.WorkspaceResourceTextContent.toReadResourceResult(): ReadResourceResult {
        return ReadResourceResult(
            contents = listOf(
                TextResourceContents(
                    uri = uri,
                    mimeType = mimeType,
                    text = text,
                ),
            ),
        )
    }

    private companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val MCP_ENDPOINT_PATH = "/mcp"
    }
}
