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

    override suspend fun computeListableResources(
        context: WorkspaceMcpFeatureContext,
    ): List<dev.ghostflyby.mcp.resource.WorkspaceListableResource> = emptyList()

    override fun register(context: WorkspaceMcpFeatureRegistrationContext): WorkspaceMcpFeatureRegistration {
        context.segments {
            // server/info — static listable resource
            segment("server") {
                segment(
                    "info",
                    handler = { _, _ ->
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
                ) { params, _ ->
                    val projectKey = params["projectKey"] ?: ""
                    val info = when (val resolved = context.projectResolver.resolve(projectKey)) {
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
        return context.buildRegistration()
    }
}
