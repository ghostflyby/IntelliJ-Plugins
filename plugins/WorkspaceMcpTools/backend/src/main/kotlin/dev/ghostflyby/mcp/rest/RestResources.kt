/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import io.ktor.resources.*
import kotlinx.serialization.Serializable

internal interface ProjectProvider {
    val projectKey: String
}

internal interface FileQuery {
    val meta: Boolean
    val content: Boolean
    val exists: Boolean
    val structure: Boolean
    val force: Boolean
    val problems: Boolean
    val problemFix: Boolean
    val minSeverity: String
    val name: List<String>
    val inspection: List<String>
    val fixable: Boolean
    val groupBy: List<String>
    val limit: Int
    val timeoutMillis: Int
    val startLine: Int?
    val endLine: Int?
    val maxLines: Int?
    val aroundLine: Int?
    val radius: Int?
}


internal object Api {
    @Serializable
    @Resource("/server/info")
    internal class ServerInfo

    @Serializable
    @Resource("/projects")
    internal class Projects

    @Serializable
    @Resource("/sessions")
    internal class Sessions {
        @Serializable
        @Resource("/{sessionId}")
        internal class Id(
            internal val parent: Sessions = Sessions(),
            internal val sessionId: String,
        )
    }

    @Serializable
    @Resource("/files")
    internal class FilesEntry(
        override val meta: Boolean = false,
        override val content: Boolean = false,
        override val exists: Boolean = false,
        override val structure: Boolean = false,
        override val force: Boolean = false,
        override val problems: Boolean = false,
        override val problemFix: Boolean = false,
        override val minSeverity: String = "WARNING",
        override val name: List<String> = emptyList(),
        override val inspection: List<String> = emptyList(),
        override val fixable: Boolean = false,
        override val groupBy: List<String> = emptyList(),
        override val limit: Int = 200,
        override val timeoutMillis: Int = 20_000,
        override val startLine: Int? = null,
        override val endLine: Int? = null,
        override val maxLines: Int? = null,
        override val aroundLine: Int? = null,
        override val radius: Int? = null,
    ) : FileQuery {
        @Serializable
        @Resource("/{path...}")
        internal class File(
            internal val parent: FilesEntry,
            internal val path: List<String> = emptyList(),
        )
    }

    @Serializable
    @Resource("/glob")
    internal class GlobEntry(
        internal val limit: Int = 0,
        internal val glob: List<String> = emptyList(),
    ) {
        @Serializable
        @Resource("/{path...}")
        internal class Glob(
            internal val parent: GlobEntry,
            internal val path: List<String> = emptyList(),
        )
    }

    @Serializable
    @Resource("/search/text")
    internal class SearchTextEntry(
        internal val query: String = "",
        internal val regex: Boolean = false,
        internal val caseSensitive: Boolean = true,
        internal val wholeWord: Boolean = false,
        internal val context: List<String> = listOf("string", "comment", "other"),
        internal val fileFilter: String? = null,
        internal val limit: Int = 100,
    ) {
        @Serializable
        @Resource("/{path...}")
        internal class SearchText(
            internal val parent: SearchTextEntry,
            internal val path: List<String> = emptyList(),
        )
    }

    @Serializable
    @Resource("/search/symbols")
    internal class SearchSymbolsEntry(
        internal val query: String = "",
        internal val libraries: Boolean = false,
        internal val kind: String? = null,
        internal val limit: Int = 50,
        internal val timeoutMillis: Int = 20_000,
    )

    @Serializable
    @Resource("/search/files")
    internal class SearchFilesEntry(
        internal val query: String = "",
        internal val limit: Int = 50,
        internal val timeoutMillis: Int = 20_000,
    )

    @Serializable
    @Resource("/inspections")
    internal class InspectionsEntry(
        internal val minSeverity: String = "WARNING",
        internal val name: List<String> = emptyList(),
        internal val inspection: List<String> = emptyList(),
        internal val fixable: Boolean = false,
        internal val groupBy: List<String> = emptyList(),
        internal val limit: Int = 200,
        internal val timeoutMillis: Int = 20_000,
    ) {
        @Serializable
        @Resource("/{path...}")
        internal class Path(
            internal val parent: InspectionsEntry,
            internal val path: List<String> = emptyList(),
        )
    }

    @Serializable
    @Resource("/navigation/{path...}")
    internal class NavigationPath(
        internal val path: List<String> = emptyList(),
    )

    @Serializable
    @Resource("/projects/{projectKey}")
    internal class Project(
        override val projectKey: String,
    ) : ProjectProvider {
        @Serializable
        @Resource("/roots")
        internal class Roots(
            internal val parent: Project,
        )

        @Serializable
        @Resource("/roots/{rootId}")
        internal class Root(
            internal val parent: Project,
            internal val rootId: String,
        ) : ProjectProvider by parent
    }
}
