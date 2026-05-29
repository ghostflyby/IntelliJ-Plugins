/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.route

import dev.ghostflyby.mcp.route.resources.ProjectFileResource
import dev.ghostflyby.mcp.route.resources.ProjectResource
import dev.ghostflyby.mcp.route.resources.VfsResource
import io.ktor.resources.*
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Test

internal class WorkspaceResourceUriFormatTest {
    private val format = WorkspaceResourceUriFormat()

    @Test
    fun `template uri includes parent resource path`() {
        assertEquals(
            "ij-workspace://{instanceKey}/projects/{projectKey}/files/{relativePath}{?meta,content,exists,structure,glob}",
            format.templateUri(ProjectFileResource.serializer().descriptor),
        )
    }

    @Test
    fun `encode includes parent resource path params`() {
        val uri = format.encodeToString(
            ProjectFileResource.serializer(),
            ProjectFileResource(
                parent = ProjectResource("project-a"),
                relativePath = "src/main/App.kt",
                meta = "",
            ),
        )

        assertEquals("ij-workspace://{instanceKey}/projects/project-a/files/src/main/App.kt?meta&exists=false&structure=false", uri)
    }

    @Test
    fun `decode creates parent resource from path params`() {
        val resource = format.decodeFromString(
            ProjectFileResource.serializer(),
            "ij-workspace://iu-1/projects/project-a/files/src/main/App.kt?content",
        )

        assertEquals("project-a", resource.parent.projectKey)
        assertEquals("src/main/App.kt", resource.relativePath)
        assertEquals("", resource.content)
    }

    @Test
    fun `encode omits absent nullable query params`() {
        val uri = format.encodeToString(
            VfsResource.serializer(),
            VfsResource(rawVfsUrl = "file:///tmp/file.kt"),
        )

        assertEquals("ij-workspace://{instanceKey}/vfs/file:///tmp/file.kt?exists=false&structure=false", uri)
    }

    @Test
    fun `encode includes present nullable query params`() {
        val uri = format.encodeToString(
            VfsResource.serializer(),
            VfsResource(rawVfsUrl = "file:///tmp/file.kt", meta = "length", content = ""),
        )

        assertEquals("ij-workspace://{instanceKey}/vfs/file:///tmp/file.kt?meta=length&content&exists=false&structure=false", uri)
    }

    @Test
    fun `decode captures tail with embedded question mark`() {
        val resource = format.decodeFromString(
            VfsResource.serializer(),
            "ij-workspace://iu-1/vfs/file:///tmp/file.kt?line=10",
        )

        assertEquals("file:///tmp/file.kt?line=10", resource.rawVfsUrl)
        assertEquals(null, resource.meta)
        assertEquals(null, resource.content)
    }

    @Test
    fun `decode captures key-only query as empty string`() {
        val resource = format.decodeFromString(
            VfsResource.serializer(),
            "ij-workspace://iu-1/vfs/file:///tmp/file.kt?meta",
        )

        assertEquals("file:///tmp/file.kt", resource.rawVfsUrl)
        assertEquals("", resource.meta)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode rejects mismatched literal path`() {
        format.decodeFromString(
            VfsResource.serializer(),
            "ij-workspace://iu-1/not-vfs/file:///tmp/file.kt",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `tail path parameter must be last`() {
        format.templateUri(InvalidTailResource.serializer().descriptor)
    }

    @Serializable
    @Resource("/bad/{tail...}/after")
    private data class InvalidTailResource(val tail: String)
}
