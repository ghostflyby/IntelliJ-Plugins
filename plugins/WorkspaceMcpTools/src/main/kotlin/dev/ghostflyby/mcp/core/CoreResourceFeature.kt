/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.core

import com.intellij.openapi.application.readAction
import dev.ghostflyby.mcp.core.CoreResourceFeature.Companion.PROJECT_SEGMENT
import dev.ghostflyby.mcp.resource.APPLICATION_JSON_MIME_TYPE
import dev.ghostflyby.mcp.resource.segment.SegmentId
import dev.ghostflyby.mcp.sdk.*
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.serialization.json.Json

/**
 * Core metadata feature: server/info, projects, and projects/{projectKey} resources.
 *
 * Registered via the segment-based URI tree. The [PROJECT_SEGMENT] anchor
 * is extensible — other features hang their project-scoped resources under it.
 */
internal class CoreResourceFeature : WorkspaceMcpFeature {
    override val featureName: String = "core"

    companion object {
        /** Anchor for project-scoped resource sub-trees. */
        val PROJECT_SEGMENT = SegmentId.next()
    }

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    override fun WorkspaceMcpFeatureRegistrationContext.register(): WorkspaceMcpFeatureRegistration {
        segments {
            // server/info — static listable resource
            segment("server") {
                segment(
                    "info",
                    handler = { _, _, _ ->
                        val instanceKey = workspaceInstanceKey()
                        val info = readAction {
                            mapOf("instanceKey" to instanceKey, "version" to "1.0.0")
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
                    },
                )
            }

            // projects — listable
            segment("projects") {
                template(
                    paramName = "projectKey",
                    id = PROJECT_SEGMENT,
                    extensible = true,
                ) { params, anc, request ->
                    val projectKey = params["projectKey"] ?: ""
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
            }
        }
        return buildRegistration()
    }
}
