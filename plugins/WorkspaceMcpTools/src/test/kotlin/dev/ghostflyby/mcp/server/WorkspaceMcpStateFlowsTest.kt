/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.server

import dev.ghostflyby.mcp.sdk.WorkspaceMcpStateFlows
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class WorkspaceMcpStateFlowsTest {
    @Test
    fun `same resource uri update advances generation`() {
        val stateFlows = WorkspaceMcpStateFlows()
        val uri = "ij-workspace://iu-1/vfs/file:///tmp/a.kt"

        stateFlows.resourceContentChanged(setOf(uri))
        val first = stateFlows.resourceUpdates.value
        stateFlows.resourceContentChanged(setOf(uri))
        val second = stateFlows.resourceUpdates.value

        assertEquals(setOf(uri), first.uris)
        assertEquals(setOf(uri), second.uris)
        assertTrue(second.uriGenerations.getValue(uri) > first.uriGenerations.getValue(uri))
    }

    @Test
    fun `resource uri updates retain previously changed uri keys`() {
        val stateFlows = WorkspaceMcpStateFlows()
        val firstUri = "ij-workspace://iu-1/vfs/file:///tmp/a.kt"
        val secondUri = "ij-workspace://iu-1/vfs/file:///tmp/b.kt"

        stateFlows.resourceContentChanged(setOf(firstUri))
        stateFlows.resourceContentChanged(setOf(secondUri))

        assertEquals(setOf(firstUri, secondUri), stateFlows.resourceUpdates.value.uris)
    }

    @Test
    fun `same session list change advances generation`() {
        val stateFlows = WorkspaceMcpStateFlows()

        stateFlows.sessionResourcesChanged("session-a")
        val first = stateFlows.perSessionResourceListChanges.value
        stateFlows.sessionResourcesChanged("session-a")
        val second = stateFlows.perSessionResourceListChanges.value

        assertEquals(setOf("session-a"), first.sessionIds)
        assertEquals(setOf("session-a"), second.sessionIds)
        assertTrue(second.sessionGenerations.getValue("session-a") > first.sessionGenerations.getValue("session-a"))
    }

    @Test
    fun `session list changes retain previously changed session keys`() {
        val stateFlows = WorkspaceMcpStateFlows()

        stateFlows.sessionResourcesChanged("session-a")
        stateFlows.sessionResourcesChanged("session-b")

        assertEquals(setOf("session-a", "session-b"), stateFlows.perSessionResourceListChanges.value.sessionIds)
    }

    @Test
    fun `global resource list change advances generation`() {
        val stateFlows = WorkspaceMcpStateFlows()

        val initial = stateFlows.globalResourceListChanges.value
        stateFlows.globalResourcesChanged()

        assertTrue(stateFlows.globalResourceListChanges.value > initial)
    }
}
