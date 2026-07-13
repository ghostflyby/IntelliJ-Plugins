/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal class SpotlessProjectServiceTest : BasePlatformTestCase() {
    private lateinit var scope: CoroutineScope
    private lateinit var spotless: SpotlessProjectService

    override fun setUp() {
        super.setUp()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        spotless = SpotlessProjectService(project, scope)
    }

    override fun tearDown() {
        try {
            if (::spotless.isInitialized) {
                spotless.dispose()
            }
            scope.cancel()
        } finally {
            super.tearDown()
        }
    }

    fun testCanFormatAndFormatUseSpotlessDaemonResponses() {
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { query ->
                    if (query.contains("dryrun=")) {
                        DaemonResponse(HttpStatusCode.OK)
                    } else {
                        DaemonResponse(HttpStatusCode.OK, "formatted-content")
                    }
                },
            ),
        )
        val provider = TestDaemonProvider(
            projectPath = projectBasePath(),
            host = SpotlessDaemonHost.Localhost(25252),
        )
        spotless.daemonProviderLookup = { provider }
        val virtualFile = createProjectFile("src/Test.kt", "unformatted-content")

        assertFalse(spotless.canFormatSync(virtualFile))
        assertTrue(waitUntil { spotless.canFormatSync(virtualFile) })
        assertTrue(runBlocking { spotless.canFormat(virtualFile) })
        assertEquals(
            SpotlessFormatResult.Dirty("formatted-content"),
            runBlocking { spotless.format(virtualFile, "unformatted-content") },
        )
        assertTrue(spotless.canFormatSync(virtualFile))
        assertEquals(1, provider.startCount)

        releaseDaemon(provider)
    }

    fun testCanFormatSyncRefreshesRetryableMissesWithoutFileEdits() {
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { DaemonResponse(HttpStatusCode.OK) },
            ),
        )
        val provider = TestDaemonProvider(
            projectPath = projectBasePath(),
            host = SpotlessDaemonHost.Localhost(25252),
        )
        val currentProvider = AtomicReference<SpotlessDaemonProvider?>()
        spotless.daemonProviderLookup = { currentProvider.get() }
        val virtualFile = createProjectFile("src/RetryableMiss.kt", "content")

        assertFalse(spotless.canFormatSync(virtualFile))

        Thread.sleep(100)
        currentProvider.set(provider)
        spotless.canFormatSync(virtualFile)

        assertTrue(waitUntil { spotless.canFormatSync(virtualFile) })

        releaseDaemon(provider)
    }

    fun testCanFormatSyncCacheIsScopedPerFile() {
        val provider = TestDaemonProvider(
            projectPath = projectBasePath(),
            host = SpotlessDaemonHost.Localhost(25252),
        )
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { DaemonResponse(HttpStatusCode.OK) },
            ),
        )
        spotless.daemonProviderLookup = { provider }
        val virtualFile = createProjectFile("src/ScopedCache.kt", "content")
        val otherVirtualFile = createProjectFile("src/OtherScopedCache.kt", "content")

        assertTrue(waitUntil { spotless.canFormatSync(virtualFile) })
        assertFalse(spotless.canFormatSync(otherVirtualFile))

        releaseDaemon(provider)
    }

    fun testCanFormatSyncRevalidatesCachedTrueAfterDaemonFailure() {
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { query ->
                    if (query.contains("dryrun=")) {
                        DaemonResponse(HttpStatusCode.OK)
                    } else {
                        DaemonResponse(HttpStatusCode.OK, "formatted-content")
                    }
                },
            ),
        )
        val provider = TestDaemonProvider(
            projectPath = projectBasePath(),
            host = SpotlessDaemonHost.Localhost(25252),
        )
        spotless.daemonProviderLookup = { provider }
        val virtualFile = createProjectFile("src/Revalidate.kt", "content")

        assertTrue(waitUntil { spotless.canFormatSync(virtualFile) })

        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { throw IOException("connection refused") },
                format = { DaemonResponse(HttpStatusCode.OK, "formatted-content") },
            ),
        )

        assertTrue(spotless.canFormatSync(virtualFile))
        assertEquals(
            SpotlessFormatResult.Error("Spotless Daemon is not responding"),
            runBlocking { spotless.format(virtualFile, "content") },
        )
        assertTrue(waitUntil { !spotless.canFormatSync(virtualFile) })

        releaseDaemon(provider)
    }

    fun testReleaseDaemonInvalidatesCachedCanFormatSyncResult() {
        val stopCount = AtomicInteger()
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { query ->
                    if (query.contains("dryrun=")) {
                        DaemonResponse(HttpStatusCode.OK)
                    } else {
                        DaemonResponse(HttpStatusCode.OK, "formatted-content")
                    }
                },
                stop = {
                    stopCount.incrementAndGet()
                    DaemonResponse(HttpStatusCode.OK)
                },
            ),
        )
        val provider = TestDaemonProvider(
            projectPath = projectBasePath(),
            host = SpotlessDaemonHost.Localhost(25252),
        )
        spotless.daemonProviderLookup = { provider }
        val virtualFile = createProjectFile("src/Cached.kt", "content")

        assertTrue(waitUntil { spotless.canFormatSync(virtualFile) })

        assertEquals(1, spotless.releaseAllDaemons())
        spotless.daemonProviderLookup = { null }
        assertFalse(spotless.canFormatSync(virtualFile))
        assertTrue(waitUntil { provider.completionCount.get() == 1 })
        assertEquals(1, stopCount.get())
        spotless.daemonProviderLookup = { provider }
        assertTrue(waitUntil { spotless.canFormatSync(virtualFile) })

        releaseDaemon(provider)
    }

    fun testProviderProcessTerminationRemovesDaemonAndInvalidatesCachedCanFormatSyncResult() {
        val stopCount = AtomicInteger()
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { DaemonResponse(HttpStatusCode.OK) },
                stop = {
                    stopCount.incrementAndGet()
                    DaemonResponse(HttpStatusCode.OK)
                },
            ),
        )
        val provider = TestDaemonProvider(
            projectPath = projectBasePath(),
            host = SpotlessDaemonHost.Localhost(25252),
        )
        spotless.daemonProviderLookup = { provider }
        val virtualFile = createProjectFile("src/NaturalExit.kt", "content")

        assertTrue(runBlocking { spotless.canFormat(virtualFile) })

        provider.terminate()

        assertTrue(waitUntil { !spotless.hasRunningDaemons() })
        assertTrue(waitUntil { provider.completionCount.get() == 1 })
        assertEquals(0, stopCount.get())
        assertFalse(spotless.canFormatSync(virtualFile))
    }

    fun testProviderSwitchStopsOldDaemonBeforeNewHandleWins() {
        val stopCount = AtomicInteger()
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { DaemonResponse(HttpStatusCode.OK) },
                stop = {
                    stopCount.incrementAndGet()
                    DaemonResponse(HttpStatusCode.OK)
                },
            ),
        )
        val oldProvider = TestDaemonProvider(
            projectPath = projectBasePath(),
            host = SpotlessDaemonHost.Localhost(25252),
        )
        val newProvider = TestDaemonProvider(
            projectPath = projectBasePath(),
            host = SpotlessDaemonHost.Localhost(25253),
        )
        val currentProvider = AtomicReference<SpotlessDaemonProvider>(oldProvider)
        spotless.daemonProviderLookup = { currentProvider.get() }
        val virtualFile = createProjectFile("src/ProviderSwitch.kt", "content")

        assertTrue(runBlocking { spotless.canFormat(virtualFile) })

        currentProvider.set(newProvider)
        assertEquals(SpotlessFormatResult.Clean, runBlocking { spotless.format(virtualFile, "content") })

        assertEquals(1, oldProvider.startCount)
        assertTrue(waitUntil { oldProvider.completionCount.get() == 1 })
        assertEquals(1, stopCount.get())
        assertEquals(1, newProvider.startCount)

        releaseDaemon(newProvider)
    }

    fun testProjectDisposeStopsRunningDaemonsAndCancelsDaemonScopes() {
        val stopCount = AtomicInteger()
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { DaemonResponse(HttpStatusCode.OK) },
                stop = {
                    stopCount.incrementAndGet()
                    DaemonResponse(HttpStatusCode.OK)
                },
            ),
        )
        val provider = TestDaemonProvider(
            projectPath = projectBasePath(),
            host = SpotlessDaemonHost.Localhost(25252),
        )
        spotless.daemonProviderLookup = { provider }
        val virtualFile = createProjectFile("src/Dispose.kt", "content")

        assertTrue(runBlocking { spotless.canFormat(virtualFile) })

        spotless.dispose()

        assertTrue(waitUntil { provider.completionCount.get() == 1 })
        assertEquals(1, stopCount.get())
        assertFalse(spotless.hasRunningDaemons())
        spotless = SpotlessProjectService(project, scope)
    }

    fun testCanFormatReturnsFalseWhenFileIsNotCovered() {
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { DaemonResponse(HttpStatusCode.NotFound) },
            ),
        )
        val provider = TestDaemonProvider(
            projectPath = projectBasePath(),
            host = SpotlessDaemonHost.Localhost(25252),
        )
        spotless.daemonProviderLookup = { provider }
        val virtualFile = createProjectFile("src/NotCovered.kt", "content")

        assertFalse(runBlocking { spotless.canFormat(virtualFile) })
        assertEquals(
            SpotlessFormatResult.NotCovered,
            runBlocking { spotless.format(virtualFile, "content") },
        )

        releaseDaemon(provider)
    }

    fun testFormatReturnsErrorWhenDaemonHealthCheckFails() {
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { throw IOException("connection refused") },
                format = { DaemonResponse(HttpStatusCode.OK) },
            ),
        )
        val provider = TestDaemonProvider(
            projectPath = projectBasePath(),
            host = SpotlessDaemonHost.Localhost(25252),
        )
        spotless.daemonProviderLookup = { provider }
        val virtualFile = createProjectFile("src/HealthCheck.kt", "content")

        assertEquals(
            SpotlessFormatResult.Error("Spotless Daemon is not responding"),
            runBlocking { spotless.format(virtualFile, "content") },
        )
        assertFalse(runBlocking { spotless.canFormat(virtualFile) })

        releaseDaemon(provider)
    }

    fun testReleaseAllDaemonsStopsProjectDaemons() {
        val stopCount = AtomicInteger()
        spotless.client = SpotlessDaemonClient(
            testHttpClient(
                healthCheck = { DaemonResponse(HttpStatusCode.OK) },
                format = { DaemonResponse(HttpStatusCode.OK) },
                stop = {
                    stopCount.incrementAndGet()
                    DaemonResponse(HttpStatusCode.OK)
                },
            ),
        )
        val provider = TestDaemonProvider(
            projectPath = projectBasePath(),
            host = SpotlessDaemonHost.Localhost(25252),
        )
        spotless.daemonProviderLookup = { provider }
        val virtualFile = createProjectFile("src/ReleaseAll.kt", "content")

        assertTrue(runBlocking { spotless.canFormat(virtualFile) })

        assertEquals(1, spotless.releaseAllDaemons())
        assertTrue(waitUntil { provider.completionCount.get() == 1 })
        assertEquals(1, stopCount.get())
        assertFalse(spotless.hasRunningDaemons())
    }

    private fun createProjectFile(relativePath: String, content: String): VirtualFile {
        val path = projectBasePath().resolve(relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        return requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)) {
            "Failed to create VirtualFile for $path"
        }
    }

    private fun projectBasePath(): Path = Path.of(requireNotNull(project.basePath))

    private fun releaseDaemon(provider: TestDaemonProvider) {
        val expectedCompletions = provider.startCount
        assertEquals(1, spotless.releaseAllDaemons())
        assertTrue(waitUntil { provider.completionCount.get() >= expectedCompletions })
    }

    private fun waitUntil(condition: () -> Boolean): Boolean {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2_000)
        while (System.nanoTime() < deadlineNanos) {
            if (condition()) {
                return true
            }
            Thread.sleep(25)
        }
        return condition()
    }

    private fun testHttpClient(
        healthCheck: () -> DaemonResponse,
        format: (query: String) -> DaemonResponse,
        stop: () -> DaemonResponse = { DaemonResponse(HttpStatusCode.OK) },
    ): HttpClient = HttpClient(MockEngine) {
        engine {
            addHandler { request ->
                val response = when (request.method) {
                    HttpMethod.Get ->
                        if (request.url.encodedPath == "/") healthCheck() else DaemonResponse(HttpStatusCode.MethodNotAllowed)

                    HttpMethod.Post ->
                        when (request.url.encodedPath) {
                            "/" -> format(request.url.encodedQuery)
                            "/stop" -> stop()
                            else -> DaemonResponse(HttpStatusCode.MethodNotAllowed)
                        }

                    else -> DaemonResponse(HttpStatusCode.MethodNotAllowed)
                }
                respond(
                    content = response.body,
                    status = response.status,
                    headers = headersOf("Content-Type", "text/plain"),
                )
            }
        }
    }

    private data class DaemonResponse(
        val status: HttpStatusCode,
        val body: String = "",
    )

    private class TestDaemonProvider(
        private var projectPath: Path,
        val host: SpotlessDaemonHost,
    ) : SpotlessDaemonProvider {
        var startCount: Int = 0
            private set

        val completionCount = AtomicInteger()
        private val lastDaemonScope = AtomicReference<CoroutineScope>()

        fun terminate() {
            lastDaemonScope.get()?.cancel("test daemon process terminated")
        }

        override fun isApplicableTo(project: Project): Boolean = true

        override fun findTarget(project: Project, virtualFile: VirtualFile): SpotlessDaemonTarget =
            SpotlessDaemonTarget(projectPath, requireNotNull(virtualFile.toNioPath()))

        override suspend fun startDaemon(
            project: Project,
            externalProject: Path,
            daemonScope: CoroutineScope,
        ): SpotlessDaemonHost {
            startCount += 1
            lastDaemonScope.set(daemonScope)
            daemonScope.coroutineContext.job.invokeOnCompletion {
                completionCount.incrementAndGet()
            }
            return host
        }
    }
}
