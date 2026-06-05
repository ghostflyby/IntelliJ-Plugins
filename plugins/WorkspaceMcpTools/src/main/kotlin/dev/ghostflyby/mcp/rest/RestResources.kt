/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import io.ktor.resources.*
import io.ktor.server.application.*
import kotlinx.serialization.Serializable

// ── Path-only resource classes ─────────────────────────────

public object Api {
    @Serializable
    @Resource("/server/info")
    public class ServerInfo

    @Serializable
    @Resource("/projects")
    public class Projects

    @Serializable
    @Resource("/projects/{projectKey}")
    public class Project(
        public val projectKey: String,
    ) {
        @Serializable
        @Resource("/roots")
        public class Roots(
            public val parent: Project,
        )

        @Serializable
        @Resource("/roots/{rootId}")
        public class Root(
            public val parent: Project,
            public val rootId: String,
        ) {
            @Serializable
            @Resource("/files/{relativePath...}")
            public class File(
                public val parent: Root,
                public val relativePath: String = "",
            )
        }

        @Serializable
        @Resource("/glob/{rootId}")
        public class GlobEntry(
            public val parent: Project,
            public val rootId: String,
        ) {
            @Serializable
            @Resource("/{relativePath...}")
            public class Glob(
                public val parent: GlobEntry,
                public val relativePath: String = "",
            )
        }
    }

    @Serializable
    @Resource("/vfs/{rawVfsUrl...}")
    public class Vfs(
        public val rawVfsUrl: String,
    )
}

// ── Typed query parameter helpers ──────────────────────────

/** Strongly-typed file content query extracted from [ApplicationCall]. */
public class FileQuery(private val call: ApplicationCall) {
    public val meta: String? get() = call.request.queryParameters["meta"]
    public val content: Boolean get() = call.request.queryParameters["content"] != null
    public val exists: Boolean get() = call.request.queryParameters["exists"] != null
    public val structure: Boolean get() = call.request.queryParameters["structure"] != null
    public val glob: List<String> get() = call.request.queryParameters.getAll("glob").orEmpty()
    public val force: Boolean get() = call.request.queryParameters["force"]?.toBooleanStrictOrNull() == true
    public val rawForce: Boolean get() = call.request.queryParameters["force"] != null
}
