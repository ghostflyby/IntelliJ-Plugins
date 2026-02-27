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

package dev.ghostflyby.dcevm.agent

import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.file.Path

internal class HotswapAgentLaunchResolverTest {

    @Test
    fun `returns null and does not invoke provider when hotswap agent is disabled`() {
        val project = fakeProject()
        var invoked = false

        val resolved = resolveHotswapAgentJarPath(
            enabled = false,
            project = project,
            provider = CachedAgentJarProvider {
                invoked = true
                Path.of("unused")
            },
        )

        assertNull(resolved)
        assertTrue(!invoked)
    }

    @Test
    fun `returns null when provider has no cached jar yet`() {
        val project = fakeProject()
        var invoked = false

        val resolved = resolveHotswapAgentJarPath(
            enabled = true,
            project = project,
            provider = CachedAgentJarProvider {
                invoked = true
                null
            },
        )

        assertNull(resolved)
        assertTrue(invoked)
    }

    @Test
    fun `returns provider jar path when cache is available`() {
        val project = fakeProject()
        val expected = Path.of("cached", "hotswap-agent.jar")

        val resolved = resolveHotswapAgentJarPath(
            enabled = true,
            project = project,
            provider = CachedAgentJarProvider { expected },
        )

        assertEquals(expected, resolved)
    }

    private fun fakeProject(): Project {
        return Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Byte.TYPE -> 0.toByte()
                java.lang.Short.TYPE -> 0.toShort()
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                java.lang.Double.TYPE -> 0.0
                java.lang.Character.TYPE -> '\u0000'
                else -> null
            }
        } as Project
    }
}
