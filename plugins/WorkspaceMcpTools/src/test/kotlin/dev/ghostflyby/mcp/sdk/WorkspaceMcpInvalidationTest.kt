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
    fun `session state checks direct subscriptions`() {
        val state = WorkspaceMcpSessionState { null }
        state.recordResourceSubscription("a", "ij-workspace://iu-1/vfs/file:///tmp/a.kt")
        state.recordResourceSubscription("b", "ij-workspace://iu-1/vfs/file:///tmp/b.kt")

        assertTrue(state.isSubscribed("a", "ij-workspace://iu-1/vfs/file:///tmp/a.kt"))
        assertFalse(state.isSubscribed("a", "ij-workspace://iu-1/vfs/file:///tmp/b.kt"))
    }

    @Test
    fun `subscribed session lookup ignores inactive sessions`() {
        val state = WorkspaceMcpSessionState { null }
        val uri = "ij-workspace://iu-1/vfs/file:///tmp/a.kt"
        state.recordResourceSubscription("active", uri)
        state.recordResourceSubscription("inactive", uri)

        assertEquals(
            listOf("active"),
            state.subscribedSessionIds(activeSessionIds = setOf("active"), resourceUri = uri),
        )
    }
}
