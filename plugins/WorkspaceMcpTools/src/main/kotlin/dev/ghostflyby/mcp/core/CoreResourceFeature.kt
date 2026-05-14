/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.core

import com.intellij.openapi.application.readAction
import dev.ghostflyby.mcp.resource.APPLICATION_JSON_MIME_TYPE
import dev.ghostflyby.mcp.resource.WorkspaceListableResource
import dev.ghostflyby.mcp.resource.listableProjectInfoUri
import dev.ghostflyby.mcp.resource.listableProjectsUri
import dev.ghostflyby.mcp.resource.listableServerInfoUri
import dev.ghostflyby.mcp.resource.WORKSPACE_URI_SCHEME
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeature
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureContext
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureRegistration
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureRegistrationContext
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolver
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolution
import dev.ghostflyby.mcp.sdk.workspaceInstanceKey
import dev.ghostflyby.mcp.sdk.workspaceProjectKey
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.serialization.json.Json

/**
 * Core metadata feature: server/info, projects, and projects/{projectKey} resources.
 *
 * These are global (app-level) resources that do not require a Project context
 * for listing. They are entirely listable; no templates are registered.
 */
internal class CoreResourceFeature : WorkspaceMcpFeature {
    override val featureName: String = "core"

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    override suspend fun computeListableResources(context: WorkspaceMcpFeatureContext): List<WorkspaceListableResource> {
        val instanceKey = workspaceInstanceKey()
        val projects = readAction { context.projectResolver.openProjects() }
        return buildList {
            add(
                WorkspaceListableResource(
                    uri = listableServerInfoUri(instanceKey),
                    name = "Workspace MCP server info",
                    description = "IDE runtime and server metadata.",
                    mimeType = APPLICATION_JSON_MIME_TYPE,
                ),
            )
            add(
                WorkspaceListableResource(
                    uri = listableProjectsUri(instanceKey),
                    name = "Open projects",
                    description = "List of open IDE projects.",
                    mimeType = APPLICATION_JSON_MIME_TYPE,
                ),
            )
            projects.forEach { project ->
                val projectKey = workspaceProjectKey(project)
                add(
                    WorkspaceListableResource(
                        uri = listableProjectInfoUri(instanceKey, projectKey),
                        name = "Project info: ${project.name}",
                        description = "Project metadata for key $projectKey.",
                        mimeType = APPLICATION_JSON_MIME_TYPE,
                    ),
                )
            }
        }
    }

    override fun register(context: WorkspaceMcpFeatureRegistrationContext): WorkspaceMcpFeatureRegistration {
        // Core metadata resources are fully listable; no templates or tools needed.
        return context.buildRegistration()
    }

    internal suspend fun tryReadCoreListable(
        uri: String,
        instanceKey: String,
        projectResolver: WorkspaceProjectResolver,
    ): ReadResourceResult? {
        if (!uri.startsWith(WORKSPACE_URI_SCHEME)) return null
        val afterScheme = uri.removePrefix(WORKSPACE_URI_SCHEME)
        val firstSlash = afterScheme.indexOf('/')
        if (firstSlash < 0) return null
        val path = afterScheme.substring(firstSlash + 1)
        return when {
            path == "server/info" -> serverInfoContent(uri, instanceKey)
            path == "projects" -> projectsListContent(uri, projectResolver)
            path.startsWith("projects/") && !path.substringAfter("projects/").contains('/') -> {
                val pk = path.substringAfter("projects/")
                if (pk.isBlank()) null else projectInfoContent(uri, pk, projectResolver)
            }
            else -> null
        }
    }

    private suspend fun serverInfoContent(uri: String, instanceKey: String): ReadResourceResult {
        val info = readAction { mapOf("instanceKey" to instanceKey, "version" to "1.0.0") }
        return ReadResourceResult(
            contents = listOf(TextResourceContents(uri = uri, mimeType = APPLICATION_JSON_MIME_TYPE, text = json.encodeToString(info))),
        )
    }

    private suspend fun projectsListContent(uri: String, projectResolver: WorkspaceProjectResolver): ReadResourceResult {
        val projects = readAction { projectResolver.openProjects() }.map { project ->
            mapOf("projectKey" to workspaceProjectKey(project), "name" to project.name, "basePath" to (project.basePath ?: ""))
        }
        return ReadResourceResult(
            contents = listOf(TextResourceContents(uri = uri, mimeType = APPLICATION_JSON_MIME_TYPE, text = json.encodeToString(projects))),
        )
    }

    private suspend fun projectInfoContent(uri: String, projectKey: String, projectResolver: WorkspaceProjectResolver): ReadResourceResult {
        val resolved = projectResolver.resolve(projectKey = projectKey)
        val info = when (resolved) {
            is WorkspaceProjectResolution.Resolved -> mapOf(
                "projectKey" to projectKey,
                "name" to resolved.project.name,
                "basePath" to (resolved.project.basePath ?: ""),
            )
            is WorkspaceProjectResolution.Unresolved -> mapOf(
                "error" to resolved.message,
                "projectKey" to projectKey,
            )
        }
        return ReadResourceResult(
            contents = listOf(TextResourceContents(uri = uri, mimeType = APPLICATION_JSON_MIME_TYPE, text = json.encodeToString(info))),
        )
    }
}
