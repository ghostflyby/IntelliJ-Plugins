/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import dev.ghostflyby.mcp.route.AncestorContext
import dev.ghostflyby.mcp.route.ResourceRouteSnapshot
import dev.ghostflyby.mcp.route.WorkspaceMcpCall
import io.modelcontextprotocol.kotlin.sdk.server.Server

internal class WorkspaceMcpPrimitiveRegistry(
    private val sessionState: WorkspaceMcpSessionState,
) {
    private val resourceUris = linkedSetOf<String>()
    private val templateUris = linkedSetOf<String>()

    fun syncResources(activeServer: Server, snapshot: ResourceRouteSnapshot) {
        val nextResourceUris = linkedSetOf<String>()
        val nextTemplateUris = linkedSetOf<String>()

        snapshot.resources.forEach { entry ->
            if (entry.isParameterized) {
                nextTemplateUris += entry.uri
                if (entry.uri !in templateUris) {
                    activeServer.addResourceTemplate(
                        uriTemplate = entry.uri,
                        name = entry.name,
                        description = entry.description,
                        mimeType = entry.mimeType,
                    ) { request, vars ->
                        val segmentIndex = entry.paramToSegmentId.entries.associate { (paramName, segmentId) ->
                            segmentId to (vars[paramName] ?: "")
                        }
                        entry.handler(
                            WorkspaceMcpCall(
                                connection = this,
                                request = request,
                                ancestors = AncestorContext(vars, segmentIndex),
                                sessionState = sessionState,
                            ),
                        )
                    }
                }
            } else {
                val concreteUri = entry.uri.withCurrentInstanceKey()
                nextResourceUris += concreteUri
                if (concreteUri !in resourceUris) {
                    activeServer.addResource(
                        uri = concreteUri,
                        name = entry.name,
                        description = entry.description,
                        mimeType = entry.mimeType,
                    ) { request ->
                        entry.handler(
                            WorkspaceMcpCall(
                                connection = this,
                                request = request,
                                ancestors = AncestorContext(emptyMap()),
                                sessionState = sessionState,
                            ),
                        )
                    }
                }
            }
        }

        (resourceUris - nextResourceUris).forEach { activeServer.removeResource(it) }
        (templateUris - nextTemplateUris).forEach { activeServer.removeResourceTemplate(it) }

        resourceUris.clear()
        resourceUris += nextResourceUris
        templateUris.clear()
        templateUris += nextTemplateUris
    }

    fun clear() {
        resourceUris.clear()
        templateUris.clear()
    }

    private fun String.withCurrentInstanceKey(): String =
        replace("{instanceKey}", workspaceInstanceKey())
}
