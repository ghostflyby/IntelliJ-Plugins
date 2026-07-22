/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless.gradle

import com.intellij.openapi.project.Project
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import dev.ghostflyby.spotless.api.SpotlessDaemonEndpoint
import dev.ghostflyby.spotless.api.SpotlessDaemonRunContext
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

@TestApplication
internal class SpotlessGradleExtensionTest {
    private val projectPathFixture = tempPathFixture()
    private val projectFixture = projectFixture(pathFixture = projectPathFixture, openAfterCreation = true)
    private val project by projectFixture

    @Test
    suspend fun `natural process termination publishes endpoint and cleans in fixed order`() {
        val workingDirectory = projectPathFixture.get().resolve("natural-lifetime")
        val cleanupOrder = mutableListOf<String>()
        val context = TestRunContext(project, projectPathFixture.get())
        lateinit var process: FakeGradleDaemonProcess

        runSpotlessGradleDaemonLifetime(
            context = context,
            createWorkingDirectory = { workingDirectory },
            startProcess = { _, _, _, _, onTerminated ->
                FakeGradleDaemonProcess(
                    initScript = workingDirectory.resolve("init.gradle"),
                    onStart = onTerminated,
                ).also { process = it }
            },
            cleanupProcess = {
                cleanupOrder.add("process")
                it?.destroyProcess()
            },
            cleanupDirectory = { cleanupOrder.add("directory") },
        )

        assertEquals(
            SpotlessDaemonEndpoint.UnixSocket(workingDirectory.resolve("spotless-daemon.sock")),
            context.endpoint.await(),
        )
        assertEquals(listOf("process", "directory"), cleanupOrder)
        assertEquals(1, process.destroyCount)
    }

    @Test
    suspend fun `core cancellation cleans process before working directory`() = coroutineScope {
        val workingDirectory = projectPathFixture.get().resolve("cancelled-lifetime")
        val cleanupOrder = mutableListOf<String>()
        val context = TestRunContext(project, projectPathFixture.get())
        lateinit var process: FakeGradleDaemonProcess
        val lifetime = async {
            runSpotlessGradleDaemonLifetime(
                context = context,
                createWorkingDirectory = { workingDirectory },
                startProcess = { _, _, _, _, _ ->
                    FakeGradleDaemonProcess(workingDirectory.resolve("init.gradle")).also { process = it }
                },
                cleanupProcess = {
                    cleanupOrder.add("process")
                    it?.destroyProcess()
                },
                cleanupDirectory = { cleanupOrder.add("directory") },
            )
        }
        context.endpoint.await()

        lifetime.cancelAndJoin()

        assertEquals(listOf("process", "directory"), cleanupOrder)
        assertEquals(1, process.destroyCount)
    }

    @Test
    suspend fun `cancellation during working directory creation retains cleanup ownership`() = coroutineScope {
        val workingDirectory = projectPathFixture.get().resolve("cancelled-directory-creation")
        val creationStarted = CompletableDeferred<Unit>()
        val allowCreationToFinish = CountDownLatch(1)
        var directoryCleanupCount = 0
        var cleanedDirectory: Path? = null
        var processCreationCount = 0
        val context = TestRunContext(project, projectPathFixture.get())
        val lifetime = async {
            runSpotlessGradleDaemonLifetime(
                context = context,
                createWorkingDirectory = {
                    creationStarted.complete(Unit)
                    allowCreationToFinish.await()
                    workingDirectory
                },
                startProcess = { _, _, _, _, _ ->
                    processCreationCount++
                    error("Process creation must not run after cancellation")
                },
                cleanupProcess = {},
                cleanupDirectory = {
                    cleanedDirectory = it
                    directoryCleanupCount++
                },
            )
        }
        creationStarted.await()

        lifetime.cancel()
        allowCreationToFinish.countDown()
        lifetime.join()

        assertEquals(0, processCreationCount)
        assertEquals(workingDirectory, cleanedDirectory)
        assertEquals(1, directoryCleanupCount)
        assertFalse(context.endpoint.isCompleted)
    }

    @Test
    suspend fun `cancellation during process creation destroys acquired process`() = coroutineScope {
        val workingDirectory = projectPathFixture.get().resolve("cancelled-process-creation")
        val processCreationStarted = CompletableDeferred<Unit>()
        val allowProcessCreationToFinish = CountDownLatch(1)
        val context = TestRunContext(project, projectPathFixture.get())
        lateinit var process: FakeGradleDaemonProcess
        var cleanedProcess: SpotlessGradleDaemonProcess? = null
        var directoryCleanupCount = 0
        val lifetime = async {
            runSpotlessGradleDaemonLifetime(
                context = context,
                createWorkingDirectory = { workingDirectory },
                startProcess = { _, _, _, _, _ ->
                    processCreationStarted.complete(Unit)
                    allowProcessCreationToFinish.await()
                    FakeGradleDaemonProcess(workingDirectory.resolve("init.gradle")).also { process = it }
                },
                cleanupProcess = {
                    cleanedProcess = it
                    it?.destroyProcess()
                },
                cleanupDirectory = { directoryCleanupCount++ },
            )
        }
        processCreationStarted.await()

        lifetime.cancel()
        allowProcessCreationToFinish.countDown()
        lifetime.join()

        assertEquals(process, cleanedProcess)
        assertEquals(0, process.startCount)
        assertEquals(1, process.destroyCount)
        assertEquals(1, directoryCleanupCount)
        assertFalse(context.endpoint.isCompleted)
    }

    @Test
    suspend fun `cancellation during process notification does not publish endpoint`() = coroutineScope {
        val workingDirectory = projectPathFixture.get().resolve("cancelled-process-notification")
        val processStartEntered = CompletableDeferred<Unit>()
        val allowProcessStartToFinish = CountDownLatch(1)
        val context = TestRunContext(project, projectPathFixture.get())
        lateinit var process: FakeGradleDaemonProcess
        var directoryCleanupCount = 0
        val lifetime = async(Dispatchers.Default) {
            runSpotlessGradleDaemonLifetime(
                context = context,
                createWorkingDirectory = { workingDirectory },
                startProcess = { _, _, _, _, _ ->
                    FakeGradleDaemonProcess(
                        initScript = workingDirectory.resolve("init.gradle"),
                        beforeStart = {
                            processStartEntered.complete(Unit)
                            allowProcessStartToFinish.await()
                        },
                    ).also { process = it }
                },
                cleanupProcess = { it?.destroyProcess() },
                cleanupDirectory = { directoryCleanupCount++ },
            )
        }
        processStartEntered.await()

        lifetime.cancel()
        allowProcessStartToFinish.countDown()
        lifetime.join()

        assertEquals(1, process.startCount)
        assertEquals(1, process.destroyCount)
        assertEquals(1, directoryCleanupCount)
        assertFalse(context.endpoint.isCompleted)
    }

    @Test
    suspend fun `process start failure still cleans process and working directory`() {
        val workingDirectory = projectPathFixture.get().resolve("failed-start")
        val cleanupOrder = mutableListOf<String>()
        val context = TestRunContext(project, projectPathFixture.get())
        lateinit var process: FakeGradleDaemonProcess

        val failure = runCatching {
            runSpotlessGradleDaemonLifetime(
                context = context,
                createWorkingDirectory = { workingDirectory },
                startProcess = { _, _, _, _, _ ->
                    FakeGradleDaemonProcess(
                        initScript = workingDirectory.resolve("init.gradle"),
                        startFailure = IOException("process start failed"),
                    ).also { process = it }
                },
                cleanupProcess = {
                    cleanupOrder.add("process")
                    it?.destroyProcess()
                },
                cleanupDirectory = { cleanupOrder.add("directory") },
            )
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertFalse(context.endpoint.isCompleted)
        assertEquals(listOf("process", "directory"), cleanupOrder)
        assertEquals(1, process.destroyCount)
    }

    private class TestRunContext(
        override val project: Project,
        override val externalProjectRoot: Path,
    ) : SpotlessDaemonRunContext {
        val endpoint = CompletableDeferred<SpotlessDaemonEndpoint>()

        override suspend fun publishEndpoint(endpoint: SpotlessDaemonEndpoint) {
            check(this.endpoint.complete(endpoint))
        }
    }

    private class FakeGradleDaemonProcess(
        override val initScript: Path,
        private val beforeStart: () -> Unit = {},
        private val onStart: () -> Unit = {},
        private val startFailure: Throwable? = null,
    ) : SpotlessGradleDaemonProcess {
        var startCount: Int = 0
            private set
        var destroyCount: Int = 0
            private set

        override fun start() {
            startCount++
            beforeStart()
            startFailure?.let { throw it }
            onStart()
        }

        override fun destroyProcess() {
            destroyCount++
        }
    }
}
