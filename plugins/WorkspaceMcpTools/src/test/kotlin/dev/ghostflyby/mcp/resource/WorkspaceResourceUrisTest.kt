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

package dev.ghostflyby.mcp.resource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class WorkspaceResourceUrisTest {

    @Test
    fun `round trips file uri`() {
        assertRoundTrip("file:///tmp/workspace/file.txt")
    }

    @Test
    fun `round trips jar uri`() {
        assertRoundTrip("jar:///tmp/workspace/lib.jar!/pkg/Foo.class")
    }

    @Test
    fun `round trips jrt uri`() {
        assertRoundTrip("jrt:///java.base/java/lang/Object.class")
    }

    @Test
    fun `round trips uri with spaces`() {
        assertRoundTrip("file:///tmp/with space/alpha beta.txt")
    }

    @Test
    fun `round trips uri with bang`() {
        assertRoundTrip("jar:///tmp/lib.jar!/pkg/with!bang.txt")
    }

    @Test
    fun `returns null for unknown scheme`() {
        assertNull(tryDecodeWorkspaceResourceUri("unknown:///tmp/data"))
    }

    @Test
    fun `returns null for invalid prefix`() {
        assertNull(tryDecodeWorkspaceResourceUri("ij-workspace-vfs:/tmp/data"))
        assertNull(tryDecodeWorkspaceResourceUri("ij-workspace-document:/tmp/data"))
    }

    @Test
    fun `returns null for blank raw url`() {
        assertNull(tryDecodeWorkspaceResourceUri(VFS_RESOURCE_PREFIX))
        assertNull(tryDecodeWorkspaceResourceUri(DOCUMENT_RESOURCE_PREFIX))
    }

    @Test
    fun `rejects blank raw url when encoding`() {
        assertThrowsIllegalArgument { vfsResourceUri("   ") }
        assertThrowsIllegalArgument { documentResourceUri("") }
    }

    @Test
    fun `decodes prefixed uri without parsing`() {
        val raw = "jar:///tmp/archive.jar!/path with spaces/and!bang"
        val vfsUri = vfsResourceUri(raw)
        val documentUri = documentResourceUri(raw)

        assertTrue(vfsUri.startsWith(VFS_RESOURCE_PREFIX))
        assertTrue(documentUri.startsWith(DOCUMENT_RESOURCE_PREFIX))
        assertEquals(raw, rawVfsUrlFromVfsResourceUri(vfsUri))
        assertEquals(raw, rawVfsUrlFromDocumentResourceUri(documentUri))
    }

    @Test
    fun `rejects missing prefix when decoding`() {
        assertThrowsIllegalArgument { rawVfsUrlFromVfsResourceUri("file:///tmp/data") }
        assertThrowsIllegalArgument { rawVfsUrlFromDocumentResourceUri("file:///tmp/data") }
    }

    private fun assertRoundTrip(rawVfsUrl: String) {
        val vfsUri = vfsResourceUri(rawVfsUrl)
        val documentUri = documentResourceUri(rawVfsUrl)

        assertEquals(rawVfsUrl, rawVfsUrlFromVfsResourceUri(vfsUri))
        assertEquals(rawVfsUrl, rawVfsUrlFromDocumentResourceUri(documentUri))

        val decodedVfs = tryDecodeWorkspaceResourceUri(vfsUri)
        val decodedDocument = tryDecodeWorkspaceResourceUri(documentUri)
        assertTrue(decodedVfs != null)
        assertTrue(decodedDocument != null)
        assertEquals(WorkspaceResourceKind.VFS, decodedVfs!!.kind)
        assertEquals(WorkspaceResourceKind.DOCUMENT, decodedDocument!!.kind)
        assertEquals(rawVfsUrl, decodedVfs.rawVfsUrl)
        assertEquals(rawVfsUrl, decodedDocument.rawVfsUrl)
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException.")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
