/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.server

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
        val state = WorkspaceMcpSessionState()
        state.recordSessionConnected("a")
        state.recordSessionConnected("b")
        state.recordResourceSubscription("a", "ij-workspace://iu-1/vfs/file:///tmp/a.kt")
        state.recordResourceSubscription("b", "ij-workspace://iu-1/vfs/file:///tmp/b.kt")

        assertTrue(state.isSubscribed("a", "ij-workspace://iu-1/vfs/file:///tmp/a.kt"))
        assertFalse(state.isSubscribed("a", "ij-workspace://iu-1/vfs/file:///tmp/b.kt"))
    }

    @Test
    fun `closing session clears subscriptions and active state`() {
        val state = WorkspaceMcpSessionState()
        val uri = "ij-workspace://iu-1/vfs/file:///tmp/a.kt"
        state.recordSessionConnected("active")
        state.recordResourceSubscription("active", uri)

        assertTrue(state.hasActiveSessions())
        assertTrue(state.hasResourceSubscriptions())
        assertTrue(state.isSubscribed("active", uri))

        state.recordSessionClosed("active")

        assertFalse(state.hasActiveSessions())
        assertFalse(state.hasResourceSubscriptions())
        assertFalse(state.isSubscribed("active", uri))
    }
}
