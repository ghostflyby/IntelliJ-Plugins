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
import dev.ghostflyby.spotless.SpotlessDaemonEndpoint
import dev.ghostflyby.spotless.SpotlessDaemonProvider
import dev.ghostflyby.spotless.SpotlessDaemonStartContext
import dev.ghostflyby.spotless.SpotlessDaemonTarget
import kotlinx.coroutines.*
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.div

internal class SpotlessGradleExtension : SpotlessDaemonProvider {
    private val logger = logger<SpotlessGradleExtension>()

    override suspend fun startDaemon(context: SpotlessDaemonStartContext): SpotlessDaemonEndpoint {
        val project = context.project
        val lifecycle = context.lifecycle
        val workingDirectory: Path = withContext(Dispatchers.IO + NonCancellable) {
            Files.createTempDirectory(null).also { directory ->
                lifecycle.registerCleanup {
                    cleanupWorkingDirectory(directory)
                }
            }
        }
        val unixSocketPath = workingDirectory / "spotless-daemon.sock"
        val endpoint = SpotlessDaemonEndpoint.UnixSocket(unixSocketPath)
        try {
            val process = withContext(Dispatchers.IO + NonCancellable) {
                startGradleSpotlessDaemon(
                    project,
                    context.externalProjectRoot,
                    unixSocketPath,
                    workingDirectory,
                ) {
                    lifecycle.requestClose("Spotless daemon process terminated")
                }.also { daemonProcess ->
                    lifecycle.registerCleanup {
                        cleanupGradleDaemonProcess(daemonProcess)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            process.start()
        } catch (error: Throwable) {
            lifecycle.requestClose("Spotless daemon failed to start")
            throw error
        }
        return endpoint
    }

    override fun resolveTarget(
        project: Project,
        file: VirtualFile,
    ): SpotlessDaemonTarget? {
        val ioPath = file.toNioPathOrNull() ?: return null
        val abs = ioPath.toAbsolutePath().normalize()
        val settings = project.service<SpotlessGradleSettings>()

        val rootDirs = GradleSettings.getInstance(project).linkedProjectsSettings
            .mapNotNull { it.externalProjectPath }
            .map { Path(it).toAbsolutePath().normalize() }
            .filter(settings::isSpotlessEnabledForProjectDir)

        val externalProject = rootDirs
            .filter { abs.startsWith(it) }
            .maxByOrNull { it.nameCount }
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
