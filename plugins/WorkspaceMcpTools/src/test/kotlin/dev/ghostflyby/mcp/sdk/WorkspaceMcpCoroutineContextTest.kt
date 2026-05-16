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

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

internal class WorkspaceMcpCoroutineContextTest {

    @Test
    fun `nullable getters return null when absent`() {
        assertNull(EmptyCoroutineContext.workspaceMcpCall)
        assertNull(EmptyCoroutineContext.workspaceMcpProject)
    }

    @Test
    fun `requireWorkspaceMcpCallContext throws when missing`() {
        var threw = false
        try { EmptyCoroutineContext.requireWorkspaceMcpCallContext }
        catch (_: IllegalStateException) { threw = true }
        assert(threw)
    }

    @Test
    fun `requireWorkspaceMcpProjectContext throws when missing`() {
        var threw = false
        try { EmptyCoroutineContext.requireWorkspaceMcpProjectContext }
        catch (_: IllegalStateException) { threw = true }
        assert(threw)
    }

    @Test
    fun `CallContext stores values`() {
        val ctx = EmptyCoroutineContext.withWorkspaceMcpCallContext(sessionId = "s1", instanceKey = "iu-63341")
        val call = ctx.requireWorkspaceMcpCallContext
        assertEquals("s1", call.sessionId)
        assertEquals("iu-63341", call.instanceKey)
    }

    @Test
    fun `CallContext allows nullable sessionId and roots`() {
        val ctx = EmptyCoroutineContext.withWorkspaceMcpCallContext(instanceKey = "ic-8080", roots = null)
        assertNull(ctx.requireWorkspaceMcpCallContext.sessionId)
        assertEquals("ic-8080", ctx.requireWorkspaceMcpCallContext.instanceKey)
        assertNull(ctx.requireWorkspaceMcpCallContext.roots)
    }

    @Test
    fun `CallContext stores roots when provided`() {
        val roots = WorkspaceMcpRootsSnapshot(listOf("file:///p1", "file:///p2"))
        val ctx = EmptyCoroutineContext.withWorkspaceMcpCallContext(instanceKey = "iu-63341", roots = roots)
        assertEquals(2, ctx.requireWorkspaceMcpCallContext.roots?.rootUris?.size)
    }

    @Test
    fun `nullable getters return non-null when present`() {
        val ctx = EmptyCoroutineContext.withWorkspaceMcpCallContext(instanceKey = "test")
        assertNotNull(ctx.workspaceMcpCall)
        assertNull(ctx.workspaceMcpProject)
    }

    @Test
    fun `currentWorkspaceProject throws when project context missing`() {
        var threw = false
        try {
            runBlocking {
                currentWorkspaceProject()
            }
        } catch (_: IllegalStateException) {
            threw = true
        }
        assert(threw)
    }

    @Test
    fun `currentWorkspaceProject extension throws when project context missing`() {
        var threw = false
        try { EmptyCoroutineContext.currentWorkspaceProject }
        catch (_: IllegalStateException) { threw = true }
        assert(threw)
    }

    @Test
    fun `default parameters work`() {
        val ctx = EmptyCoroutineContext.withWorkspaceMcpCallContext(instanceKey = "default-test")
        assertNull(ctx.requireWorkspaceMcpCallContext.sessionId)
        assertEquals("default-test", ctx.requireWorkspaceMcpCallContext.instanceKey)
        assertNull(ctx.requireWorkspaceMcpCallContext.roots)
    }

}
