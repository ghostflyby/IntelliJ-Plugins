/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mac.recents

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URI
import kotlin.coroutines.ContinuationInterceptor
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
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
    fun `sync forces first empty replace before enabling skip`() {
        val bridge = RecordingBridge()
        val coordinator = coordinator(bridge)

        runBlocking {
            coordinator.sync(emptyList())
            coordinator.sync(emptyList())
        }

        assertEquals(listOf("replace:"), bridge.operations)
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
    fun `scheduler resets debounce window for the latest snapshot`() {
        runTest {
            val bridge = RecordingBridge()
            val coordinator = coordinator(bridge, debounceMillis = 100L)

            coordinator.scheduleSync(recentPaths = listOf("/tmp/recent-a"))
            runCurrent()
            advanceTimeBy(80.milliseconds)
            coordinator.scheduleSync(recentPaths = listOf("/tmp/recent-c", "/tmp/recent-b"))
            advanceTimeBy(99.milliseconds)
            assertEquals(emptyList<String>(), bridge.operations)

            advanceUntilIdle()

            assertEquals(
                listOf("replace:file:///tmp/recent-b,file:///tmp/recent-c"),
                bridge.operations,
            )
        }
    }

    @Test
    fun `scheduler keeps startup path until recent projects catches up`() {
        runTest {
            val startupPath = "/tmp/startup-project.ipr"
            val bridge = RecordingBridge()
            val coordinator = coordinator(bridge, debounceMillis = 50L)

            coordinator.scheduleSync(recentPaths = emptyList(), startupProjectPath = startupPath)
            runCurrent()
            advanceUntilIdle()
            assertEquals(listOf("replace:file:///tmp/startup-project.ipr"), bridge.operations)

            bridge.operations.clear()
            coordinator.scheduleSync(recentPaths = listOf("/tmp/recent-a"))
            runCurrent()
            advanceUntilIdle()
            assertEquals(listOf("append:file:///tmp/recent-a"), bridge.operations)

            bridge.operations.clear()
            coordinator.scheduleSync(recentPaths = listOf(startupPath, "/tmp/recent-a"))
            runCurrent()
            advanceUntilIdle()
            assertEquals(
                listOf("replace:file:///tmp/recent-a,file:///tmp/startup-project.ipr"),
                bridge.operations,
            )
        }
    }

    @Test
    fun `scheduler clears directory based startup path when recent projects uses project root`() {
        runTest {
            val startupPath = "/tmp/directory-project/.idea/misc.xml"
            val recentProjectPath = "/tmp/directory-project"
            val bridge = RecordingBridge()
            val coordinator = coordinator(bridge, debounceMillis = 50L)

            coordinator.scheduleSync(recentPaths = emptyList(), startupProjectPath = startupPath)
            runCurrent()
            advanceUntilIdle()
            assertEquals(listOf("replace:file:///tmp/directory-project/.idea/misc.xml"), bridge.operations)

            bridge.operations.clear()
            coordinator.scheduleSync(recentPaths = listOf(recentProjectPath))
            runCurrent()
            advanceUntilIdle()
            assertEquals(listOf("replace:file:///tmp/directory-project"), bridge.operations)

            bridge.operations.clear()
            coordinator.scheduleSync(recentPaths = listOf(recentProjectPath))
            runCurrent()
            advanceUntilIdle()
            assertEquals(emptyList<String>(), bridge.operations)
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
            startupProjectLookupDispatcher =
                coroutineScope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher ?: Dispatchers.IO,
            nanoTime = (coroutineScope as? TestScope)
                ?.let { testScope -> { testScope.testScheduler.currentTime * NANOS_PER_MILLISECOND } }
                ?: System::nanoTime,
        )
    }

    private fun TestScope.coordinator(
        bridge: RecordingBridge,
        debounceMillis: Long = 250L,
    ): CocoaRecentProjectsCoordinator = coordinator(
        bridge = bridge,
        coroutineScope = this,
        debounceMillis = debounceMillis,
    )

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

    private companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
