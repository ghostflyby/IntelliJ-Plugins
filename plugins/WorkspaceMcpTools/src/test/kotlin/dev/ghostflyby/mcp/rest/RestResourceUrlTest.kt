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
    fun `file resource URL keeps nested root scoped path shape`() {
        Assertions.assertEquals(
            "/api/v1/projects/project-key/roots/workspace-0/src/Main.kt",
            rootPathUrl("project-key", "workspace-0", "src/Main.kt"),
        )
    }

    @Test
    fun `file resource URL encodes explicit boolean query parameters`() {
        Assertions.assertEquals(
            "/api/v1/projects/project-key/roots/workspace-0/src/Main.kt?meta=true",
            rootPathUrl("project-key", "workspace-0", "src/Main.kt", meta = true),
        )
        Assertions.assertEquals(
            "/api/v1/projects/project-key/roots/workspace-0/src/Main.kt?force=false",
            rootPathUrl("project-key", "workspace-0", "src/Main.kt", force = false),
        )
    }

    @Test
    fun `glob resource URL preserves repeated glob query parameters`() {
        val url = globPathUrl("project-key", "workspace-0", "glob", glob = listOf("**/*.kt", "**/*.kts"))
        Assertions.assertEquals("/api/v1/projects/project-key/glob/workspace-0/glob", Url(url).encodedPath)
        Assertions.assertEquals(listOf("**/*.kt", "**/*.kts"), Url(url).parameters.getAll("glob"))
    }
}
