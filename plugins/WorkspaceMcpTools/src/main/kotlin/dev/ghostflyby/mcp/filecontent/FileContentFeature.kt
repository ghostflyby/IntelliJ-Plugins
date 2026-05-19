/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.filecontent

import dev.ghostflyby.mcp.core.CoreResourceFeature
import dev.ghostflyby.mcp.resource.TEXT_PLAIN_MIME_TYPE
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeature
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureRegistration
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureRegistrationContext
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents

/**
 * Unified content access feature.
 *
 * Routes:
 * - `vfs/{+rawVfsUrl}` — project-independent, read-only raw VFS access
 * - `files/{+relativePath}` — project-scoped file access via Document API (read+write)
 */
internal class FileContentFeature : WorkspaceMcpFeature {
    override val featureName: String = "file-content"

    override fun WorkspaceMcpFeatureRegistrationContext.register(): WorkspaceMcpFeatureRegistration {
        val projectAnchor = CoreResourceFeature.PROJECT_ROUTE

        segments {
            // -- project-independent raw VFS (read only) --
            route("vfs/{+rawVfsUrl}") {
                resource { call ->
                    val rawVfsUrl = call.ancestors["rawVfsUrl"] ?: ""
                    val uri = call.request.params.uri
                    val file = resolveFileByRawUrl(rawVfsUrl)
                    val text = readContent(file)
                    ReadResourceResult(
                        contents = listOf(
                            TextResourceContents(
                                uri = uri,
                                mimeType = TEXT_PLAIN_MIME_TYPE,
                                text = text,
                            ),
                        ),
                    )
                }
                template()
            }

            // -- project-scoped files (via Document) --
            under(projectAnchor) {
                route("files/{+relativePath}") {
                    resource { call ->
                        val anc = call.ancestors
                        val projectKey = anc["projectKey"] ?: ""
                        val relativePath = anc["relativePath"] ?: ""
                        val uri = call.request.params.uri
                        val file = resolveFileByRelativePath(projectKey, relativePath, projectResolver)
                        val text = readContent(file)
                        ReadResourceResult(
                            contents = listOf(
                                TextResourceContents(
                                    uri = uri,
                                    mimeType = TEXT_PLAIN_MIME_TYPE,
                                    text = text,
                                ),
                            ),
                        )
                    }
                    template()
                }
            }
        }

        return buildRegistration()
    }
}
