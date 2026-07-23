/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless.gradle

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import dev.ghostflyby.spotless.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.div

internal class SpotlessGradleExtension : SpotlessDaemonProvider {
    private val logger = logger<SpotlessGradleExtension>()

    override val id: String = "dev.ghostflyby.spotless.gradle"

    override fun state(project: Project): StateFlow<SpotlessDaemonProviderState> =
        project.service<SpotlessGradleSettings>().providerState

    override suspend fun startDaemon(context: SpotlessDaemonStartContext): SpotlessDaemonHandle =
        startSpotlessGradleDaemon(
            context = context,
            createWorkingDirectory = { Files.createTempDirectory(null) },
            startProcess = ::startGradleSpotlessDaemon,
            cleanupProcess = ::cleanupGradleDaemonProcess,
            cleanupDirectory = ::cleanupWorkingDirectory,
        )

    override fun resolveTarget(
        project: Project,
        file: VirtualFile,
    ): SpotlessDaemonTarget? {
        val ioPath = file.toNioPathOrNull() ?: return null
        val abs = ioPath.toAbsolutePath().normalize()
        val externalProject = state(project).value.projects
            .map(ExternalProject::root)
            .filter { abs.startsWith(it) }
            .maxByOrNull(Path::getNameCount)
            ?: return null
        return SpotlessDaemonTarget(externalProject, abs)
    }

    private fun cleanupWorkingDirectory(workingDirectory: Path) {
        val deleted = workingDirectory.toFile().deleteRecursively()
        if (!deleted && Files.exists(workingDirectory)) {
            logger.warn("Failed to delete daemon temp directory after stop (daemon detached): $workingDirectory")
        }
    }
}

internal suspend fun startSpotlessGradleDaemon(
    context: SpotlessDaemonStartContext,
    createWorkingDirectory: () -> Path,
    startProcess: (Project, Path, Path, Path, () -> Unit) -> SpotlessGradleDaemonProcess,
    cleanupProcess: (SpotlessGradleDaemonProcess?) -> Unit,
    cleanupDirectory: (Path) -> Unit,
): SpotlessDaemonHandle {
    val terminated = CompletableDeferred<Unit>()
    var workingDirectory: Path? = null
    var process: SpotlessGradleDaemonProcess? = null
    var ownershipTransferred = false

    suspend fun cleanup() {
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                cleanupProcess(process)
            } finally {
                workingDirectory?.let(cleanupDirectory)
            }
        }
    }

    try {
        withContext(Dispatchers.IO) {
            workingDirectory = createWorkingDirectory()
        }
        currentCoroutineContext().ensureActive()
        val directory = checkNotNull(workingDirectory)
        val unixSocketPath = directory / "spotless-daemon.sock"
        withContext(Dispatchers.IO) {
            process = startProcess(
                context.project,
                context.externalProjectRoot,
                unixSocketPath,
                directory,
            ) {
                terminated.complete(Unit)
            }
        }
        currentCoroutineContext().ensureActive()
        checkNotNull(process).start()
        currentCoroutineContext().ensureActive()
        val handle = context.launchHandle(SpotlessDaemonEndpoint.UnixSocket(unixSocketPath)) {
            try {
                terminated.await()
            } finally {
                cleanup()
            }
        }
        ownershipTransferred = true
        return handle
    } finally {
        if (!ownershipTransferred) {
            cleanup()
        }
    }
}
