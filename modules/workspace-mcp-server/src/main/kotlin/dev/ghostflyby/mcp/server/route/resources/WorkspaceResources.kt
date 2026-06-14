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


@Resource("/projects")
public object Projects

@Serializable
@Resource("/{projectKey}")
public data class ProjectResource(val parent: Projects, val projectKey: String)

@Serializable
@Resource("/roots")
public data class Roots(val parent: ProjectResource)

@Serializable
@Resource("/{rootId}")
public data class RootResource(val parent: Roots, val rootId: String)

@Serializable
@Resource("/vfs/{rawVfsUrl...}")
public data class VfsResource(
    public val rawVfsUrl: String,
)

@Serializable
@Resource("/{relativePath...}")
public data class RootFileResource(
    public val parent: RootResource,
    public val relativePath: String,
)
