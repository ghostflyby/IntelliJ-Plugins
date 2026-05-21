/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class WorkspaceMcpInvalidationTest {
    @Test
    fun `subscription URI filter accepts current resource spaces`() {
        assertTrue(
            WorkspaceMcpResourceSubscriptionService.isWorkspaceResourceUri(
                "ij-workspace://iu-1/vfs/file:///tmp/workspace/file.txt",
            ),
        )
        assertTrue(
            WorkspaceMcpResourceSubscriptionService.isWorkspaceResourceUri(
                "ij-workspace://iu-1/projects/project-a/files/src/main/Foo.kt",
            ),
        )
    }

    @Test
    fun `subscription URI filter rejects removed document resources`() {
        assertFalse(
            WorkspaceMcpResourceSubscriptionService.isWorkspaceResourceUri(
                "ij-workspace://iu-1/projects/project-a/documents/src/main/Foo.kt",
            ),
        )
    }

    @Test
    fun `resource list selector finds sessions by subscribed URI`() {
        val state = WorkspaceMcpSessionState { null }
        state.recordResourceSubscription("a", "ij-workspace://iu-1/vfs/file:///tmp/a.kt")
        state.recordResourceSubscription("b", "ij-workspace://iu-1/vfs/file:///tmp/b.kt")

        assertEquals(
            setOf("a"),
            state.sessionIdsForResourceListSelector(
                activeSessionIds = setOf("a", "b"),
                selector = ResourceListSelector.Uri("ij-workspace://iu-1/vfs/file:///tmp/a.kt"),
            ),
        )
    }

    @Test
    fun `resource list selector finds sessions by URI prefix`() {
        val state = WorkspaceMcpSessionState { null }
        state.recordResourceSubscription("a", "ij-workspace://iu-1/projects/project-a/files/src/A.kt")
        state.recordResourceSubscription("b", "ij-workspace://iu-1/projects/project-b/files/src/B.kt")

        assertEquals(
            setOf("a"),
            state.sessionIdsForResourceListSelector(
                activeSessionIds = setOf("a", "b"),
                selector = ResourceListSelector.UriPrefix("ij-workspace://iu-1/projects/project-a/files/"),
            ),
        )
    }

    @Test
    fun `resource list selector ignores inactive sessions`() {
        val state = WorkspaceMcpSessionState { null }
        state.recordResourceSubscription("inactive", "ij-workspace://iu-1/vfs/file:///tmp/a.kt")

        assertEquals(
            emptySet<String>(),
            state.sessionIdsForResourceListSelector(
                activeSessionIds = emptySet(),
                selector = ResourceListSelector.Uri("ij-workspace://iu-1/vfs/file:///tmp/a.kt"),
            ),
        )
    }
}
