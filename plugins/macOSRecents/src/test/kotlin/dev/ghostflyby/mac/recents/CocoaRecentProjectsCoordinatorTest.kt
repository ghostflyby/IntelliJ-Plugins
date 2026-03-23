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

package dev.ghostflyby.mac.recents

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URI
import kotlin.io.path.Path

internal class CocoaRecentProjectsCoordinatorTest {

    @Test
    fun `sync keeps order and removes duplicates`() {
        val bridge = RecordingBridge()
        val coordinator = coordinator(bridge)

        runBlocking {
            coordinator.sync(
                listOf(
                    uri("/tmp/recent-c"),
                    uri("/tmp/recent-b"),
                    uri("/tmp/recent-c"),
                    uri("/tmp/recent-a"),
                ),
            )
        }

        assertEquals(
            listOf(
                "replace:file:///tmp/recent-a,file:///tmp/recent-b,file:///tmp/recent-c",
            ),
            bridge.operations,
        )
    }

    @Test
    fun `sync appends incrementally for new head item`() {
        val bridge = RecordingBridge()
        val coordinator = coordinator(bridge)

        runBlocking {
            coordinator.sync(listOf(uri("/tmp/recent-b"), uri("/tmp/recent-a")))
            bridge.operations.clear()

            coordinator.sync(listOf(uri("/tmp/recent-c"), uri("/tmp/recent-b"), uri("/tmp/recent-a")))
        }

        assertEquals(listOf("append:file:///tmp/recent-c"), bridge.operations)
    }

    @Test
    fun `sync rebuilds when removal happens`() {
        val bridge = RecordingBridge()
        val coordinator = coordinator(bridge)

        runBlocking {
            coordinator.sync(listOf(uri("/tmp/recent-c"), uri("/tmp/recent-b"), uri("/tmp/recent-a")))
            bridge.operations.clear()

            coordinator.sync(listOf(uri("/tmp/recent-c"), uri("/tmp/recent-a")))
        }

        assertEquals(
            listOf(
                "replace:file:///tmp/recent-a,file:///tmp/recent-c",
            ),
            bridge.operations,
        )
    }

    @Test
    fun `startup uri is merged once and update can reuse it`() {
        val startupPath = "/tmp/startup-project.ipr"
        val startupUri = uri(startupPath)

        assertEquals(
            listOf(startupUri),
            collectTargetUris(recentPaths = emptyList(), startupProjectPaths = setOf(startupPath)),
        )

        assertEquals(
            listOf(startupUri),
            collectTargetUris(recentPaths = listOf(startupPath), startupProjectPaths = setOf(startupPath)),
        )
    }

    @Test
    fun `scheduler resets debounce window for the latest snapshot`() = runBlocking {
        val bridge = RecordingBridge()
        val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = coordinator(bridge, coordinatorScope, debounceMillis = 100L)

        try {
            coordinator.scheduleSync(recentPaths = listOf("/tmp/recent-a"))
            delay(80L)
            coordinator.scheduleSync(recentPaths = listOf("/tmp/recent-c", "/tmp/recent-b"))
            delay(60L)
            assertEquals(emptyList<String>(), bridge.operations)

            delay(120L)

            assertEquals(
                listOf("replace:file:///tmp/recent-b,file:///tmp/recent-c"),
                bridge.operations,
            )
        } finally {
            coordinatorScope.cancel()
        }
    }

    @Test
    fun `scheduler keeps startup path until recent projects catches up`() = runBlocking {
        val startupPath = "/tmp/startup-project.ipr"
        val bridge = RecordingBridge()
        val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = coordinator(bridge, coordinatorScope, debounceMillis = 50L)

        try {
            coordinator.scheduleSync(recentPaths = emptyList(), startupProjectPath = startupPath)
            delay(150L)
            assertEquals(listOf("replace:file:///tmp/startup-project.ipr"), bridge.operations)

            bridge.operations.clear()
            coordinator.scheduleSync(recentPaths = listOf("/tmp/recent-a"))
            delay(150L)
            assertEquals(listOf("append:file:///tmp/recent-a"), bridge.operations)

            bridge.operations.clear()
            coordinator.scheduleSync(recentPaths = listOf(startupPath, "/tmp/recent-a"))
            delay(150L)
            assertEquals(
                listOf("replace:file:///tmp/recent-a,file:///tmp/startup-project.ipr"),
                bridge.operations,
            )
        } finally {
            coordinatorScope.cancel()
        }
    }

    @Test
    fun `scheduler clears directory based startup path when recent projects uses project root`() = runBlocking {
        val startupPath = "/tmp/directory-project/.idea/misc.xml"
        val recentProjectPath = "/tmp/directory-project"
        val bridge = RecordingBridge()
        val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = coordinator(bridge, coordinatorScope, debounceMillis = 50L)

        try {
            coordinator.scheduleSync(recentPaths = emptyList(), startupProjectPath = startupPath)
            delay(150L)
            assertEquals(listOf("replace:file:///tmp/directory-project/.idea/misc.xml"), bridge.operations)

            bridge.operations.clear()
            coordinator.scheduleSync(recentPaths = listOf(recentProjectPath))
            delay(150L)
            assertEquals(listOf("replace:file:///tmp/directory-project"), bridge.operations)

            bridge.operations.clear()
            coordinator.scheduleSync(recentPaths = listOf(recentProjectPath))
            delay(150L)
            assertEquals(emptyList<String>(), bridge.operations)
        } finally {
            coordinatorScope.cancel()
        }
    }

    private fun coordinator(
        bridge: RecordingBridge,
        coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        debounceMillis: Long = 250L,
    ): CocoaRecentProjectsCoordinator {
        return CocoaRecentProjectsCoordinator(
            coroutineScope = coroutineScope,
            documentsBridge = bridge,
            debounceMillis = debounceMillis,
        )
    }

    private fun uri(path: String): URI = Path(path).toUri()

    private class RecordingBridge : CocoaRecentDocumentsBridge {
        val operations = mutableListOf<String>()

        override suspend fun appendRecentDocuments(uris: List<URI>) {
            operations += "append:${uris.joinToString(",")}"
        }

        override suspend fun replaceRecentDocuments(uris: List<URI>) {
            operations += "replace:${uris.joinToString(",")}"
        }
    }
}
