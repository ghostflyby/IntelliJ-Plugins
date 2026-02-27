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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy
import java.nio.file.Files

internal class HotswapAgentManagerTest {

    @Test
    fun `returns cached jar immediately without triggering warm up`() {
        val project = fakeProject()
        val tempDir = Files.createTempDirectory("hotswap-agent-cache")
        val agentJar = tempDir.resolve("hotswap-agent.jar")
        Files.writeString(agentJar, "cached")

        var progressCalls = 0
        var downloadCalls = 0
        val manager = HotswapAgentManager(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        manager.downloadHooks = HotswapAgentManager.DownloadHooks(
            cacheDirProvider = { tempDir },
            progressRunner = { _, action ->
                progressCalls++
                action()
            },
            downloadRequest = { _, _ ->
                downloadCalls++
            },
        )

        val cached = manager.getCachedAgentJarOrWarmUp(project)

        assertEquals(agentJar, cached)
        assertEquals(0, progressCalls)
        assertEquals(0, downloadCalls)
    }

    @Test
    fun `returns null when cache is missing and warms up asynchronously`() = runBlocking {
        val project = fakeProject()
        val tempDir = Files.createTempDirectory("hotswap-agent-warmup")
        val started = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        var downloadCalls = 0

        val manager = HotswapAgentManager(
            scope = this,
        )
        manager.downloadHooks = HotswapAgentManager.DownloadHooks(
            cacheDirProvider = { tempDir },
            progressRunner = { _, action ->
                started.complete(Unit)
                proceed.await()
                action()
            },
            downloadRequest = { _, target ->
                downloadCalls++
                Files.writeString(target, "downloaded")
            },
        )

        val initial = manager.getCachedAgentJarOrWarmUp(project)
        assertNull(initial)

        started.await()
        assertNull(manager.cachedAgentJarPathOrNull())

        proceed.complete(Unit)
        manager.getLocalAgentJarAsync(project).join()

        val cached = manager.cachedAgentJarPathOrNull()
        assertTrue(cached != null && Files.isRegularFile(cached))
        assertEquals(1, downloadCalls)
    }

    @Test
    fun `download failure is swallowed and keeps cache empty`() = runBlocking {
        val project = fakeProject()
        val tempDir = Files.createTempDirectory("hotswap-agent-fail")
        var downloadCalls = 0

        val manager = HotswapAgentManager(
            scope = this,
        )
        manager.downloadHooks = HotswapAgentManager.DownloadHooks(
            cacheDirProvider = { tempDir },
            progressRunner = { _, action -> action() },
            downloadRequest = { _, _ ->
                downloadCalls++
                throw IOException("network error")
            },
        )

        val initial = manager.getCachedAgentJarOrWarmUp(project)
        assertNull(initial)

        manager.getLocalAgentJarAsync(project).join()

        assertNull(manager.cachedAgentJarPathOrNull())
        assertTrue(downloadCalls >= 1)
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
