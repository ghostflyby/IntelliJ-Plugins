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

package dev.ghostflyby.spotless

import com.intellij.mock.MockProjectEx
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class SpotlessImplTest : BasePlatformTestCase() {
    private lateinit var scope: CoroutineScope
    private lateinit var spotless: SpotlessImpl

    override fun setUp() {
        super.setUp()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        spotless = SpotlessImpl(scope)
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
        spotless.http = testHttpClient(
            healthCheck = { DaemonResponse(HttpStatusCode.OK) },
            format = { query ->
                if (query.contains("dryrun=")) {
                    DaemonResponse(HttpStatusCode.OK)
                } else {
                    DaemonResponse(HttpStatusCode.OK, "formatted-content")
                }
            },
        )
        val provider = TestDaemonProvider(
            projectPath = projectBasePath(),
            host = SpotlessDaemonHost.Localhost(25252),
        )
        spotless.daemonProviderLookup = { provider }
        val virtualFile = createProjectFile("src/Test.kt", "unformatted-content")

        assertFalse(spotless.canFormatSync(project, virtualFile))
        assertTrue(waitUntil { spotless.canFormatSync(project, virtualFile) })
        assertTrue(runBlocking { spotless.canFormat(project, virtualFile) })
        assertEquals(
            SpotlessFormatResult.Dirty("formatted-content"),
            runBlocking { spotless.format(project, virtualFile, "unformatted-content") },
        )
        assertTrue(spotless.canFormatSync(project, virtualFile))
        assertEquals(1, provider.startCount)

        releaseDaemon(provider)
    }

    fun testCanFormatSyncRefreshesRetryableMissesWithoutFileEdits() {
        spotless.http = testHttpClient(
            healthCheck = { DaemonResponse(HttpStatusCode.OK) },
            format = { DaemonResponse(HttpStatusCode.OK) },
        )
        val provider = TestDaemonProvider(
            projectPath = projectBasePath(),
            host = SpotlessDaemonHost.Localhost(25252),
        )
        var currentProvider: SpotlessDaemonProvider? = null
        spotless.daemonProviderLookup = { currentProvider }
        val virtualFile = createProjectFile("src/RetryableMiss.kt", "content")

        assertFalse(spotless.canFormatSync(project, virtualFile))

        Thread.sleep(100)
        currentProvider = provider

        assertTrue(waitUntil { spotless.canFormatSync(project, virtualFile) })

        releaseDaemon(provider)
    }

    fun testCanFormatSyncCacheIsScopedPerProject() {
        spotless.http = testHttpClient(
            healthCheck = { DaemonResponse(HttpStatusCode.OK) },
            format = { DaemonResponse(HttpStatusCode.OK) },
        )
        val provider = TestDaemonProvider(
            projectPath = projectBasePath(),
            host = SpotlessDaemonHost.Localhost(25252),
        )
        val otherProject = object : MockProjectEx(testRootDisposable) {
            override fun getLocationHash(): String = "spotless-other-project"

            override fun getName(): String = "spotless-other-project"

            override fun getBasePath(): String? = project.basePath
        }
        spotless.daemonProviderLookup = { currentProject ->
            when (currentProject) {
                project -> provider
                else -> null
            }
        }
        val virtualFile = createProjectFile("src/ScopedCache.kt", "content")

        assertTrue(waitUntil { spotless.canFormatSync(project, virtualFile) })
        assertFalse(spotless.canFormatSync(otherProject, virtualFile))

        releaseDaemon(provider)
    }

    fun testCanFormatSyncRevalidatesCachedTrueAfterDaemonFailure() {
        spotless.http = testHttpClient(
            healthCheck = { DaemonResponse(HttpStatusCode.OK) },
            format = { query ->
                if (query.contains("dryrun=")) {
                    DaemonResponse(HttpStatusCode.OK)
                } else {
                    DaemonResponse(HttpStatusCode.OK, "formatted-content")
                }
            },
        )
        val provider = TestDaemonProvider(
            projectPath = projectBasePath(),
            host = SpotlessDaemonHost.Localhost(25252),
        )
        spotless.daemonProviderLookup = { provider }
        val virtualFile = createProjectFile("src/Revalidate.kt", "content")

        assertTrue(waitUntil { spotless.canFormatSync(project, virtualFile) })

        spotless.http = testHttpClient(
            healthCheck = { throw IOException("connection refused") },
            format = { DaemonResponse(HttpStatusCode.OK, "formatted-content") },
        )

        assertTrue(spotless.canFormatSync(project, virtualFile))
        assertEquals(
            SpotlessFormatResult.Error("Spotless Daemon is not responding"),
            runBlocking { spotless.format(project, virtualFile, "content") },
        )
        assertTrue(waitUntil { !spotless.canFormatSync(project, virtualFile) })

        releaseDaemon(provider)
    }

    fun testReleaseDaemonInvalidatesCachedCanFormatSyncResult() {
        spotless.http = testHttpClient(
            healthCheck = { DaemonResponse(HttpStatusCode.OK) },
            format = { query ->
                if (query.contains("dryrun=")) {
                    DaemonResponse(HttpStatusCode.OK)
                } else {
                    DaemonResponse(HttpStatusCode.OK, "formatted-content")
                }
            },
        )
        val provider = TestDaemonProvider(
            projectPath = projectBasePath(),
            host = SpotlessDaemonHost.Localhost(25252),
        )
        spotless.daemonProviderLookup = { provider }
        val virtualFile = createProjectFile("src/Cached.kt", "content")

        assertTrue(waitUntil { spotless.canFormatSync(project, virtualFile) })

        spotless.releaseDaemon(provider.host)
        assertFalse(spotless.canFormatSync(project, virtualFile))
        assertTrue(provider.stopped.await(5, TimeUnit.SECONDS))
        assertTrue(waitUntil { spotless.canFormatSync(project, virtualFile) })

        releaseDaemon(provider)
    }

    fun testCanFormatReturnsFalseWhenFileIsNotCovered() {
        spotless.http = testHttpClient(
            healthCheck = { DaemonResponse(HttpStatusCode.OK) },
            format = { DaemonResponse(HttpStatusCode.NotFound) },
        )
        val provider = TestDaemonProvider(
            projectPath = projectBasePath(),
            host = SpotlessDaemonHost.Localhost(25252),
        )
        spotless.daemonProviderLookup = { provider }
        val virtualFile = createProjectFile("src/NotCovered.kt", "content")

        assertFalse(runBlocking { spotless.canFormat(project, virtualFile) })
        assertEquals(
            SpotlessFormatResult.NotCovered,
            runBlocking { spotless.format(project, virtualFile, "content") },
        )

        releaseDaemon(provider)
    }

    fun testFormatReturnsErrorWhenDaemonHealthCheckFails() {
        spotless.http = testHttpClient(
            healthCheck = { throw IOException("connection refused") },
            format = { DaemonResponse(HttpStatusCode.OK) },
        )
        val provider = TestDaemonProvider(
            projectPath = projectBasePath(),
            host = SpotlessDaemonHost.Localhost(25252),
        )
        spotless.daemonProviderLookup = { provider }
        val virtualFile = createProjectFile("src/HealthCheck.kt", "content")

        assertEquals(
            SpotlessFormatResult.Error("Spotless Daemon is not responding"),
            runBlocking { spotless.format(project, virtualFile, "content") },
        )
        assertFalse(runBlocking { spotless.canFormat(project, virtualFile) })

        releaseDaemon(provider)
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
        spotless.releaseDaemon(provider.host)
        assertTrue(provider.stopped.await(5, TimeUnit.SECONDS))
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
    ): HttpClient = HttpClient(MockEngine) {
        engine {
            addHandler { request ->
                val response = when (request.method) {
                    HttpMethod.Get ->
                        if (request.url.encodedPath == "/") healthCheck() else DaemonResponse(HttpStatusCode.MethodNotAllowed)

                    HttpMethod.Post ->
                        when (request.url.encodedPath) {
                            "/" -> format(request.url.encodedQuery)
                            "/stop" -> DaemonResponse(HttpStatusCode.OK)
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
        private val projectPath: Path,
        val host: SpotlessDaemonHost,
    ) : SpotlessDaemonProvider {
        var startCount: Int = 0
            private set

        val stopped = CountDownLatch(1)

        override fun isApplicableTo(project: Project): Boolean = true

        override suspend fun startDaemon(
            project: Project,
            externalProject: Path,
        ): SpotlessDaemonHost {
            startCount += 1
            return host
        }

        override fun findExternalProjectPath(project: Project, virtualFile: VirtualFile): Path = projectPath

        override suspend fun afterDaemonStopped(
            daemon: SpotlessDaemonHost,
            reason: String,
        ) {
            stopped.countDown()
        }
    }
}
