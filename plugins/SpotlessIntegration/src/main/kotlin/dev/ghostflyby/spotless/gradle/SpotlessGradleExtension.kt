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
import dev.ghostflyby.spotless.SpotlessDaemonHost
import dev.ghostflyby.spotless.SpotlessDaemonProvider
import dev.ghostflyby.spotless.SpotlessDaemonTarget
import kotlinx.coroutines.*
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.Path
import kotlin.io.path.div

internal class SpotlessGradleExtension : SpotlessDaemonProvider {
    private val logger = logger<SpotlessGradleExtension>()

    override fun isApplicableTo(
        project: Project,
    ): Boolean {
        val settings = project.service<SpotlessGradleSettings>()
        return GradleSettings.getInstance(project).linkedProjectsSettings
            .any { settings.isSpotlessEnabledForProjectDir(Path(it.externalProjectPath)) }
    }

    override suspend fun startDaemon(
        project: Project,
        externalProject: Path,
        daemonScope: CoroutineScope,
    ): SpotlessDaemonHost {
        val workingDirectory: Path = withContext(Dispatchers.IO) {
            Files.createTempDirectory(null)
        }
        val unixSocketPath = workingDirectory / "spotless-daemon.sock"
        val host = SpotlessDaemonHost.Unix(unixSocketPath, workingDirectory)
        var process: SpotlessGradleDaemonProcess? = null
        val cleanupOnce = AtomicBoolean(false)
        val completionHandle = daemonScope.coroutineContext.job.invokeOnCompletion {
            cleanup(host, process, "daemon scope completed", cleanupOnce)
        }
        try {
            process = withContext(Dispatchers.IO) {
                startGradleSpotlessDaemon(
                    project,
                    externalProject,
                    unixSocketPath,
                    host,
                ) {
                    daemonScope.cancel("Spotless daemon process terminated")
                }
            }
            process.start()
        } catch (error: Throwable) {
            completionHandle.dispose()
            daemonScope.cancel("Spotless daemon failed to start", error)
            withContext(Dispatchers.IO) {
                cleanup(host, process, "daemon start failed", cleanupOnce)
            }
            throw error
        }
        return host
    }

    override fun findTarget(
        project: Project,
        virtualFile: VirtualFile,
    ): SpotlessDaemonTarget? {
        val ioPath = virtualFile.toNioPathOrNull() ?: return null
        val abs = ioPath.toAbsolutePath().normalize()

        val rootDirs = GradleSettings.getInstance(project).linkedProjectsSettings
            .mapNotNull { it.externalProjectPath }
            .map { Paths.get(it).toAbsolutePath().normalize() }

        val externalProject = rootDirs
            .filter { abs.startsWith(it) }
            .minByOrNull { it.nameCount }
            ?: return null
        return SpotlessDaemonTarget(externalProject, ioPath)
    }

    private fun cleanup(
        host: SpotlessDaemonHost.Unix,
        process: SpotlessGradleDaemonProcess?,
        reason: String,
        cleanupOnce: AtomicBoolean,
    ) {
        if (!cleanupOnce.compareAndSet(false, true)) {
            return
        }
        cleanupGradleDaemonProcess(process)
        val deleted = host.workingDirectory.toFile().deleteRecursively()
        if (!deleted && Files.exists(host.workingDirectory)) {
            logger.warn("Failed to delete daemon temp directory after stop ($reason): ${host.workingDirectory}")
        }
    }
}
