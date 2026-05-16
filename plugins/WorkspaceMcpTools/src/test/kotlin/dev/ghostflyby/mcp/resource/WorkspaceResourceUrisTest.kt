/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.resource

import org.junit.Assert.*
import org.junit.Test

internal class WorkspaceResourceUrisTest {

    private companion object {
        const val IK = "iu-63341"
        const val PK = "my-project-a1b2"
    }

    // ---- new scheme builders ----

    @Test
    fun `workspaceFileUri builds correct URI`() {
        val uri = workspaceFileUri(IK, PK, "src/main/Foo.kt")
        assertTrue(uri.startsWith(WORKSPACE_URI_SCHEME))
        assertTrue(uri.contains("/projects/$PK/files/"))
    }

    @Test
    fun `workspaceDocumentUri builds correct URI`() {
        val uri = workspaceDocumentUri(IK, PK, "src/main/Foo.kt")
        assertTrue(uri.startsWith(WORKSPACE_URI_SCHEME))
        assertTrue(uri.contains("/projects/$PK/documents/"))
    }

    @Test
    fun `workspaceVfsUri builds correct URI`() {
        val uri = workspaceVfsUri(IK, PK, "file:///tmp/workspace/file.txt")
        assertTrue(uri.startsWith(WORKSPACE_URI_SCHEME))
        assertTrue(uri.contains("/projects/$PK/vfs/file:///tmp/workspace/file.txt"))
    }

    @Test
    fun `workspaceDocumentVfsUri builds correct URI`() {
        val uri = workspaceDocumentVfsUri(IK, PK, "jar:///tmp/lib.jar!/pkg/Foo.class")
        assertTrue(uri.startsWith(WORKSPACE_URI_SCHEME))
        assertTrue(uri.contains("/projects/$PK/document-vfs/"))
    }

    @Test
    fun `new scheme builders reject blank`() {
        assertThrowsIllegalArgument { workspaceFileUri(IK, PK, "") }
        assertThrowsIllegalArgument { workspaceDocumentUri(IK, PK, "  ") }
        assertThrowsIllegalArgument { workspaceVfsUri(IK, PK, "") }
        assertThrowsIllegalArgument { workspaceDocumentVfsUri(IK, PK, "  ") }
    }

    // ---- new scheme decode ----

    @Test
    fun `decodes valid workspace file URI`() {
        val uri = workspaceFileUri(IK, PK, "src/main/Foo.kt")
        val decoded = tryDecodeWorkspaceResourceUri(uri)
        assertNotNull(decoded)
        assertEquals(IK, decoded!!.instanceKey)
        assertEquals(PK, decoded.projectKey)
        assertEquals(WorkspaceResourceKind.FILES, decoded.kind)
        assertEquals("src/main/Foo.kt", decoded.tail)
    }

    @Test
    fun `decodes workspace document URI`() {
        val uri = workspaceDocumentUri(IK, PK, "src/main/Bar.kt")
        val decoded = tryDecodeWorkspaceResourceUri(uri)
        assertNotNull(decoded)
        assertEquals(WorkspaceResourceKind.DOCUMENTS, decoded!!.kind)
        assertEquals("src/main/Bar.kt", decoded.tail)
    }

    @Test
    fun `decodes workspace vfs URI with raw file scheme`() {
        val uri = workspaceVfsUri(IK, PK, "file:///tmp/workspace/file.txt")
        val decoded = tryDecodeWorkspaceResourceUri(uri)
        assertNotNull(decoded)
        assertEquals(WorkspaceResourceKind.VFS, decoded!!.kind)
        assertEquals("file:///tmp/workspace/file.txt", decoded.tail)
    }

    @Test
    fun `decodes workspace vfs URI with jar scheme`() {
        val uri = workspaceVfsUri(IK, PK, "jar:///tmp/workspace/lib.jar!/pkg/Foo.kt")
        val decoded = tryDecodeWorkspaceResourceUri(uri)
        assertNotNull(decoded)
        assertEquals("jar:///tmp/workspace/lib.jar!/pkg/Foo.kt", decoded!!.tail)
    }

    @Test
    fun `decodes workspace vfs URI with jrt scheme`() {
        val uri = workspaceVfsUri(IK, PK, "jrt:///java.base/java/lang/String.class")
        val decoded = tryDecodeWorkspaceResourceUri(uri)
        assertNotNull(decoded)
        assertEquals("jrt:///java.base/java/lang/String.class", decoded!!.tail)
    }

    @Test
    fun `decodes workspace vfs URI with spaces and bang`() {
        val uri = workspaceVfsUri(IK, PK, "jar:///tmp/with space/lib.jar!/pkg/with!bang.kt")
        val decoded = tryDecodeWorkspaceResourceUri(uri)
        assertNotNull(decoded)
        assertEquals("jar:///tmp/with space/lib.jar!/pkg/with!bang.kt", decoded!!.tail)
    }

    @Test
    fun `decodes workspace vfs URI with unknown inner scheme`() {
        val uri = workspaceVfsUri(IK, PK, "custom+scheme://host/path/value.txt")
        val decoded = tryDecodeWorkspaceResourceUri(uri)
        assertNotNull(decoded)
        assertEquals("custom+scheme://host/path/value.txt", decoded!!.tail)
    }

    @Test
    fun `returns null for unknown scheme`() {
        assertNull(tryDecodeWorkspaceResourceUri("unknown:///tmp/data"))
    }

    @Test
    fun `returns null for legacy-style prefix`() {
        assertNull(tryDecodeWorkspaceResourceUri("ij-workspace-vfs://file:///tmp/data"))
    }

    @Test
    fun `returns null for blank tail`() {
        assertNull(tryDecodeWorkspaceResourceUri("ij-workspace://$IK/projects/$PK/files/"))
    }

    @Test
    fun `returns null for missing projects segment`() {
        assertNull(tryDecodeWorkspaceResourceUri("ij-workspace://$IK/foo/bar/vfs/something"))
    }

    // ---- relative path validation ----

    @Test
    fun `workspaceFileUri rejects absolute path`() {
        assertThrowsIllegalArgument { workspaceFileUri(IK, PK, "/etc/passwd") }
    }

    @Test
    fun `workspaceDocumentUri rejects absolute path`() {
        assertThrowsIllegalArgument { workspaceDocumentUri(IK, PK, "/etc/passwd") }
    }

    @Test
    fun `workspaceFileUri rejects path with dotdot`() {
        assertThrowsIllegalArgument { workspaceFileUri(IK, PK, "src/../../outside") }
    }

    @Test
    fun `workspaceDocumentUri rejects path with dotdot`() {
        assertThrowsIllegalArgument { workspaceDocumentUri(IK, PK, "src/../../outside") }
    }

    @Test
    fun `workspaceFileUri rejects blank path`() {
        assertThrowsIllegalArgument { workspaceFileUri(IK, PK, "  ") }
    }

    @Test
    fun `workspaceDocumentUri rejects blank path`() {
        assertThrowsIllegalArgument { workspaceDocumentUri(IK, PK, "") }
    }

    @Test
    fun `tryDecodeWorkspaceResourceUri returns WorkspaceResourceUri for valid URI`() {
        val uri = workspaceFileUri(IK, PK, "src/main/Foo.kt")
        val decoded = tryDecodeWorkspaceResourceUri(uri)
        assertNotNull(decoded)
        assertEquals(IK, decoded!!.instanceKey)
        assertEquals(PK, decoded.projectKey)
        assertEquals(WorkspaceResourceKind.FILES, decoded.kind)
        assertEquals("src/main/Foo.kt", decoded.tail)
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
