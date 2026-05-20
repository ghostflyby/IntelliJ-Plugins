/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.core

import com.intellij.openapi.application.readAction
import dev.ghostflyby.mcp.PluginInfo
import dev.ghostflyby.mcp.core.CoreResourceFeature.Companion.PROJECT_ROUTE
import dev.ghostflyby.mcp.resource.APPLICATION_JSON_MIME_TYPE
import dev.ghostflyby.mcp.route.ResourceListDecision
import dev.ghostflyby.mcp.route.visibleProjects
import dev.ghostflyby.mcp.route.RouteAnchor
import dev.ghostflyby.mcp.sdk.*
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.Resource
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.serialization.json.Json

/**
 * Core metadata feature: server/info, projects, and projects/{projectKey} resources.
 *
 * Registered via the Ktor-like route DSL. The [PROJECT_ROUTE] anchor
 * is string-keyed — other features hang their project-scoped resources
 * under it using `under(RouteAnchor("projectKey"))`.
 */
internal class CoreResourceFeature : WorkspaceMcpFeature {
    override val featureName: String = "core"

    companion object {
        /** Route anchor for project-scoped resource sub-trees. */
        val PROJECT_ROUTE = RouteAnchor("projectKey")
    }

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    override fun WorkspaceMcpFeatureRegistrationContext.register(): WorkspaceMcpFeatureRegistration {
        segments {
            // server/info — static listable resource via route pattern
            route("server/info") {
                read {
                    val instanceKey = workspaceInstanceKey()
                    val info = readAction {
                        mapOf("instanceKey" to instanceKey, "version" to PluginInfo.version)
                    }
                    ReadResourceResult(
                        contents = listOf(
                            TextResourceContents(
                                uri = "",
                                mimeType = APPLICATION_JSON_MIME_TYPE,
                                text = json.encodeToString(info),
                            ),
                        ),
                    )
                }
            }

            // projects/{projectKey} — parameterized route with anchor
            route("projects/{projectKey}", anchor = PROJECT_ROUTE) {
                listResources {
                    val projects = call.visibleProjects()
                    ResourceListDecision(
                        entries = projects.map { project ->
                            Resource(
                                uri = "ij-workspace://${call.instanceKey}/projects/${project.projectKey}",
                                name = project.projectKey,
                                description = project.basePath ?: project.name,
                                mimeType = APPLICATION_JSON_MIME_TYPE,
                                title = project.name,
                            )
                        },
                        includeChildren = projects.isNotEmpty(),
                    )
                }
                read {
                    val anc = call.parameters
                    val projectKey = anc["projectKey"] ?: ""
                    val info = when (val resolved = projectResolver.resolve(projectKey)) {
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
                    ReadResourceResult(
                        contents = listOf(
                            TextResourceContents(
                                uri = "",
                                mimeType = APPLICATION_JSON_MIME_TYPE,
                                text = json.encodeToString(info),
                            ),
                        ),
                    )
                }
                listTemplates {
                    val projects = call.visibleProjects()
                    ResourceListDecision(
                        entries = emptyList(),
                        includeChildren = projects.isNotEmpty(),
                    )
                }
            }
        }
        return buildRegistration()
    }
}
