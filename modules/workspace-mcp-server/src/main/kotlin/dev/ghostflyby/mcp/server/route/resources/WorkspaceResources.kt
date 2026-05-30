/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.server.route.resources

import io.ktor.resources.*
import kotlinx.serialization.Serializable

public interface FileContentQuery {
    public val meta: String?
    public val content: String?
    public val exists: Boolean
    public val structure: Boolean
    public val glob: String?
}

@Serializable
@Resource("/server/info")
public class ServerInfoResource

@Serializable
@Resource("/projects/{projectKey}")
public data class ProjectResource(val projectKey: String)

@Serializable
@Resource("/vfs/{rawVfsUrl...}")
public data class VfsResource(
    public val rawVfsUrl: String,
    override val meta: String? = null,
    override val content: String? = null,
    override val exists: Boolean = false,
    override val structure: Boolean = false,
    override val glob: String? = null,
) : FileContentQuery

@Serializable
@Resource("/files/{relativePath...}")
public data class ProjectFileResource(
    public val parent: ProjectResource,
    public val relativePath: String,
    override val meta: String? = null,
    override val content: String? = null,
    override val exists: Boolean = false,
    override val structure: Boolean = false,
    override val glob: String? = null,
) : FileContentQuery
