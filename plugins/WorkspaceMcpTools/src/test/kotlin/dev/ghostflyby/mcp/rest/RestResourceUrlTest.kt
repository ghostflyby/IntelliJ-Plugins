/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import io.ktor.http.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

internal class RestResourceUrlTest {

    @Test
    fun `root resource URL keeps public REST prefix and root path shape`() {
        Assertions.assertEquals(
            "/api/v1/projects/project-key/roots/workspace-0",
            rootUrl("project-key", "workspace-0"),
        )
    }

    @Test
    fun `file resource URL keeps short session scoped path shape`() {
        Assertions.assertEquals(
            "/api/v1/files/src/Main.kt",
            rootPathUrl("src/Main.kt"),
        )
    }

    @Test
    fun `file resource URL encodes explicit boolean query parameters`() {
        Assertions.assertEquals(
            "/api/v1/files/src/Main.kt?meta=true",
            rootPathUrl("src/Main.kt", meta = true),
        )
        Assertions.assertEquals(
            "/api/v1/files/src/Main.kt?force=false",
            rootPathUrl("src/Main.kt", force = false),
        )
    }

    @Test
    fun `glob resource URL preserves repeated glob query parameters`() {
        val url = globPathUrl("glob", glob = listOf("**/*.kt", "**/*.kts"))
        Assertions.assertEquals("/api/v1/glob/glob", Url(url).encodedPath)
        Assertions.assertEquals(listOf("**/*.kt", "**/*.kts"), Url(url).parameters.getAll("glob"))
    }

    @Test
    fun `session resource URLs keep header scoped short path shape`() {
        Assertions.assertEquals(
            "/api/v1/sessions",
            apiUrl(Api.Sessions()),
        )
        Assertions.assertEquals(
            "/api/v1/sessions/session-id",
            apiUrl(Api.Sessions.Id(sessionId = "session-id")),
        )
        Assertions.assertEquals(
            "/api/v1/files/src/Main.kt",
            apiUrl(
                Api.FilesEntry.File(
                    parent = Api.FilesEntry(),
                    path = listOf("src", "Main.kt"),
                ),
            ),
        )

        val globUrl = apiUrl(
            Api.GlobEntry.Glob(
                parent = Api.GlobEntry(glob = listOf("**/*.kt")),
                path = listOf("src"),
            ),
            Parameters.build { append("glob", "**/*.kt") },
        )
        Assertions.assertEquals("/api/v1/glob/src", Url(globUrl).encodedPath)
        Assertions.assertEquals(listOf("**/*.kt"), Url(globUrl).parameters.getAll("glob"))

        val searchUrl = apiUrl(
            Api.SearchTextEntry.SearchText(
                parent = Api.SearchTextEntry(query = "hello"),
                path = listOf("src"),
            ),
            Parameters.build { append("query", "hello") },
        )
        Assertions.assertEquals("/api/v1/search/text/src", Url(searchUrl).encodedPath)
        Assertions.assertEquals("hello", Url(searchUrl).parameters["query"])
        Assertions.assertEquals(
            "/api/v1/navigation/src/Main.kt",
            apiUrl(Api.NavigationPath(path = listOf("src", "Main.kt"))),
        )
    }
}
