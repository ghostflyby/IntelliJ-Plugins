/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * This file is part of IntelliJ-Plugins by ghostflyby
 *
 * IntelliJ-Plugins by ghostflyby is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see
 * <https://www.gnu.org/licenses/>.
 */

package dev.ghostflyby.mcp.sdk

import dev.ghostflyby.mcp.resource.workspaceFileUri
import dev.ghostflyby.mcp.resource.workspaceVfsUri
import io.modelcontextprotocol.kotlin.sdk.types.ResourceTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

internal class WorkspaceResourceTemplateMatcherTest {

    private companion object {
        const val IK = "iu-63341"
        const val PK = "my-project-a1b2"
    }

    // ---- new scheme matcher tests ----

    @Test
    fun `new files template matches file URI`() {
        val matcher = newFilesMatcher()
        val uri = workspaceFileUri(IK, PK, "src/main/Foo.kt")
        val result = matcher.match(uri)
        assertNotNull(result)
        assertEquals(IK, result!!.variables["instanceKey"])
        assertEquals(PK, result.variables["projectKey"])
        assertEquals("src/main/Foo.kt", result.variables["relativePath"])
    }

    @Test
    fun `new vfs template matches vfs URI with file scheme`() {
        val matcher = newVfsMatcher()
        val uri = workspaceVfsUri(IK, PK, "file:///tmp/workspace/file.txt")
        val result = matcher.match(uri)
        assertNotNull(result)
        assertEquals(IK, result!!.variables["instanceKey"])
        assertEquals(PK, result.variables["projectKey"])
        assertEquals("file:///tmp/workspace/file.txt", result.variables["rawVfsUrl"])
    }

    @Test
    fun `new vfs template matches vfs URI with jar scheme`() {
        val matcher = newVfsMatcher()
        val uri = workspaceVfsUri(IK, PK, "jar:///tmp/lib.jar!/pkg/with!bang.kt")
        val result = matcher.match(uri)
        assertNotNull(result)
        assertEquals("jar:///tmp/lib.jar!/pkg/with!bang.kt", result!!.variables["rawVfsUrl"])
    }

    @Test
    fun `new files template does not match legacy-style URI`() {
        val matcher = newFilesMatcher()
        assertNull(matcher.match("ij-workspace-vfs://file:///tmp/data.txt"))
    }

    @Test
    fun `delegates unknown templates to default matcher`() {
        val matcher = WorkspaceResourceTemplateMatcherFactory.create(
            ResourceTemplate(
                uriTemplate = "demo://items/{id}",
                name = "demo",
            ),
        )
        val result = matcher.match("demo://items/alpha")
        assertEquals("alpha", result?.variables?.get("id"))
    }

    @Test
    fun `vfs matcher preserves raw VFS tail for file URL`() {
        assertVfsTailMatch("file:///tmp/workspace/file.txt")
    }

    @Test
    fun `vfs matcher preserves raw VFS tail for jar URL`() {
        assertVfsTailMatch("jar:///tmp/workspace/lib.jar!/pkg/Foo.kt")
    }

    @Test
    fun `vfs matcher preserves raw VFS tail for jrt URL`() {
        assertVfsTailMatch("jrt:///java.base/java/lang/String.class")
    }

    @Test
    fun `vfs matcher preserves spaces and bang`() {
        assertVfsTailMatch("jar:///tmp/with space/lib.jar!/pkg/with!bang.kt")
    }

    @Test
    fun `vfs matcher preserves unknown inner scheme`() {
        assertVfsTailMatch("custom+scheme://host/path/value.txt")
    }

    // ---- helpers ----

    private fun assertVfsTailMatch(rawVfsUrl: String) {
        val matcher = newVfsMatcher()
        val uri = workspaceVfsUri(IK, PK, rawVfsUrl)
        val result = matcher.match(uri)
        assertNotNull(result)
        assertEquals(rawVfsUrl, result!!.variables["rawVfsUrl"])
    }

    private fun newFilesMatcher() =
        WorkspaceResourceTemplateMatcherFactory.create(
            ResourceTemplate(
                uriTemplate = NEW_WORKSPACE_FILES_TEMPLATE,
                name = "files",
            ),
        )

    private fun newVfsMatcher() =
        WorkspaceResourceTemplateMatcherFactory.create(
            ResourceTemplate(
                uriTemplate = NEW_WORKSPACE_VFS_TEMPLATE,
                name = "vfs",
            ),
        )
}
