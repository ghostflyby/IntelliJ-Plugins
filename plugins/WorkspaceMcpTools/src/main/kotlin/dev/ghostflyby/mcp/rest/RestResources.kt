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

public interface FileQuery {
    public val meta: Boolean
    public val content: Boolean
    public val exists: Boolean
    public val structure: Boolean
    public val force: Boolean
    public val startLine: Int?
    public val endLine: Int?
    public val maxLines: Int?
    public val aroundLine: Int?
    public val radius: Int?
}


public object Api {
    @Serializable
    @Resource("/server/info")
    public class ServerInfo

    @Serializable
    @Resource("/projects")
    public class Projects

    @Serializable
    @Resource("/sessions")
    public class Sessions {
        @Serializable
        @Resource("/{sessionId}")
        public class Id(
            public val parent: Sessions = Sessions(),
            public val sessionId: String,
        )
    }

    @Serializable
    @Resource("/files")
    public class FilesEntry(
        public override val meta: Boolean = false,
        public override val content: Boolean = false,
        public override val exists: Boolean = false,
        public override val structure: Boolean = false,
        public override val force: Boolean = false,
        public override val startLine: Int? = null,
        public override val endLine: Int? = null,
        public override val maxLines: Int? = null,
        public override val aroundLine: Int? = null,
        public override val radius: Int? = null,
    ) : FileQuery {
        @Serializable
        @Resource("/{path...}")
        public class File(
            public val parent: FilesEntry,
            public val path: List<String> = emptyList(),
        )
    }

    @Serializable
    @Resource("/glob")
    public class GlobEntry(
        public val limit: Int = 0,
        public val glob: List<String> = emptyList(),
    ) {
        @Serializable
        @Resource("/{relativePath...}")
        public class Glob(
            public val parent: GlobEntry,
            public val relativePath: List<String> = emptyList(),
        )
    }

    @Serializable
    @Resource("/search/text")
    public class SearchTextEntry(
        public val query: String = "",
        public val regex: Boolean = false,
        public val caseSensitive: Boolean = true,
        public val wholeWord: Boolean = false,
        public val context: List<String> = listOf("string", "comment", "other"),
        public val fileFilter: String? = null,
        public val limit: Int = 100,
    ) {
        @Serializable
        @Resource("/{relativePath...}")
        public class SearchText(
            public val parent: SearchTextEntry,
            public val relativePath: List<String> = emptyList(),
        )
    }

    @Serializable
    @Resource("/navigation/{relativePath...}")
    public class NavigationPath(
        public val relativePath: List<String> = emptyList(),
    )

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
            public val rootId: String,
        ) : ProjectProvider by parent
    }
}
