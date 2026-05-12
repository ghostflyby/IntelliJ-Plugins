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

import dev.ghostflyby.mcp.resource.documentResourceUri
import dev.ghostflyby.mcp.resource.vfsResourceUri
import io.modelcontextprotocol.kotlin.sdk.types.ResourceTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

internal class WorkspaceResourceTemplateMatcherTest {

    @Test
    fun `captures full raw VFS tail for file URL`() {
        assertVfsMatch("file:///tmp/workspace/file.txt")
    }

    @Test
    fun `captures full raw VFS tail for jar URL`() {
        assertVfsMatch("jar:///tmp/workspace/lib.jar!/pkg/Foo.kt")
    }

    @Test
    fun `captures full raw VFS tail for jrt URL`() {
        assertVfsMatch("jrt:///java.base/java/lang/String.class")
    }

    @Test
    fun `captures spaces and bang without URI parsing`() {
        assertVfsMatch("jar:///tmp/with space/lib.jar!/pkg/with!bang.kt")
    }

    @Test
    fun `captures unknown inner scheme without validation`() {
        assertVfsMatch("custom+scheme://host/path/value.txt")
    }

    @Test
    fun `captures full raw document tail`() {
        val rawVfsUrl = "jar:///tmp/workspace/lib.jar!/pkg/Foo.kt"
        val matcher = WorkspaceResourceTemplateMatcherFactory.create(
            ResourceTemplate(
                uriTemplate = WORKSPACE_DOCUMENT_RESOURCE_TEMPLATE,
                name = "document",
            ),
        )

        val result = matcher.match(documentResourceUri(rawVfsUrl))

        assertEquals(rawVfsUrl, result?.variables?.get(RAW_VFS_URL_VARIABLE))
    }

    @Test
    fun `does not match another workspace prefix`() {
        val matcher = vfsMatcher()

        assertNull(matcher.match(documentResourceUri("file:///tmp/workspace/file.txt")))
    }

    @Test
    fun `does not match blank raw tail`() {
        val matcher = vfsMatcher()

        assertNull(matcher.match("ij-workspace-vfs://"))
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

    private fun assertVfsMatch(rawVfsUrl: String) {
        val result = vfsMatcher().match(vfsResourceUri(rawVfsUrl))

        assertEquals(rawVfsUrl, result?.variables?.get(RAW_VFS_URL_VARIABLE))
    }

    private fun vfsMatcher() =
        WorkspaceResourceTemplateMatcherFactory.create(
            ResourceTemplate(
                uriTemplate = WORKSPACE_VFS_RESOURCE_TEMPLATE,
                name = "vfs",
            ),
        )
}
