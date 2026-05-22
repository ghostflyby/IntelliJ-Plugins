/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.route.resources

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

@Serializable
@Resource("/server/info")
internal class ServerInfoResource

@Serializable
@Resource("/projects/{projectKey}")
internal data class ProjectResource(val projectKey: String)

@Serializable
@Resource("/vfs/{rawVfsUrl...}")
internal data class VfsResource(
    val rawVfsUrl: String,
    val meta: String? = null,
    val content: String? = null,
)

@Serializable
@Resource("/files/{relativePath...}")
internal data class ProjectFileResource(
    val parent: ProjectResource,
    val relativePath: String,
    val meta: String? = null,
    val content: String? = null,
)
