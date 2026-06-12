/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import io.ktor.resources.*
import kotlinx.serialization.Serializable

public interface ProjectProvider {
    public val projectKey: String
}

public interface RootProvider {
    public val rootId: String
}

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
        public override val projectKey: String,
    ) : ProjectProvider {
        @Serializable
        @Resource("/roots")
        public class Roots(
            public val parent: Project,
        )

        @Serializable
        @Resource("/roots/{rootId}")
        public class Root(
            public val parent: Project,
            public override val rootId: String,
        ) : ProjectProvider by parent, RootProvider

        @Serializable
        @Resource("/files/{rootId}")
        public class FilesEntry(
            public val parent: Project,
            public override val rootId: String,
            public val meta: Boolean = false,
            public val content: Boolean = false,
            public val exists: Boolean = false,
            public val structure: Boolean = false,
            public val force: Boolean = false,
            public val startLine: Int? = null,
            public val endLine: Int? = null,
            public val maxLines: Int? = null,
            public val aroundLine: Int? = null,
            public val radius: Int? = null,
        ) : ProjectProvider by parent, RootProvider {
            @Serializable
            @Resource("/{relativePath...}")
            public class File(
                public val parent: FilesEntry,
                public val relativePath: List<String> = emptyList(),
            ) : ProjectProvider by parent, RootProvider by parent
        }

        @Serializable
        @Resource("/glob/{rootId}")
        public class GlobEntry(
            public val parent: Project,
            public override val rootId: String,
            public val limit: Int = 0,
            public val glob: List<String> = emptyList(),
        ) : ProjectProvider by parent, RootProvider {
            @Serializable
            @Resource("/{relativePath...}")
            public class Glob(
                public val parent: GlobEntry,
                public val relativePath: List<String> = emptyList(),
            ) : ProjectProvider by parent, RootProvider by parent
        }
    }

    @Serializable
    @Resource("/vfs/{rawVfsUrl...}")
    public class Vfs(
        public val rawVfsUrl: List<String> = emptyList(),
        public val meta: Boolean = false,
        public val content: Boolean = false,
        public val exists: Boolean = false,
        public val structure: Boolean = false,
        public val force: Boolean = false,
        public val startLine: Int? = null,
        public val endLine: Int? = null,
        public val maxLines: Int? = null,
        public val aroundLine: Int? = null,
        public val radius: Int? = null,
    )
}
