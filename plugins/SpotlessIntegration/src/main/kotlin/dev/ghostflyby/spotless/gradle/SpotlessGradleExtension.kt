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
import dev.ghostflyby.spotless.Spotless
import dev.ghostflyby.spotless.SpotlessDaemonHost
import dev.ghostflyby.spotless.SpotlessDaemonProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.Path
import kotlin.io.path.div

internal class SpotlessGradleExtension : SpotlessDaemonProvider {
    private val logger = logger<SpotlessGradleExtension>()
    private val daemonProcesses = ConcurrentHashMap<SpotlessDaemonHost.Unix, SpotlessGradleDaemonProcess>()

    override fun isApplicableTo(
        project: Project,
    ): Boolean {
        val holder = project.service<SpotlessGradleStateHolder>()
        return GradleSettings.getInstance(project).linkedProjectsSettings
            .any { holder.isSpotlessEnabledForProjectDir(Path(it.externalProjectPath)) }
    }

    override suspend fun startDaemon(
        project: Project,
        externalProject: Path,
    ): SpotlessDaemonHost {
        val workingDirectory: Path = withContext(Dispatchers.IO) {
            Files.createTempDirectory(null)
        }
        val unixSocketPath = workingDirectory / "spotless-daemon.sock"
        val host = SpotlessDaemonHost.Unix(unixSocketPath, workingDirectory)
        val holder = project.service<SpotlessGradleStateHolder>()
        var process: SpotlessGradleDaemonProcess? = null
        try {
            process = withContext(Dispatchers.IO) {
                startGradleSpotlessDaemon(
                    project,
                    externalProject,
                    unixSocketPath,
                    host,
                ) {
                    holder.daemons.remove(host)
                    daemonProcesses.remove(host)
                    service<Spotless>().releaseDaemon(host)
                }
            }
            daemonProcesses[host] = process
            holder.daemons.add(host)
            process.start()
        } catch (error: Throwable) {
            daemonProcesses.remove(host)
            holder.daemons.remove(host)
            withContext(Dispatchers.IO) {
                cleanupGradleDaemonProcess(process)
                workingDirectory.toFile().deleteRecursively()
            }
            throw error
        }
        return host
    }

    override fun findExternalProjectPath(
        project: Project,
        virtualFile: VirtualFile,
    ): Path? {
        val ioPath = virtualFile.toNioPathOrNull() ?: return null
        val abs = ioPath.toAbsolutePath().normalize()

        val rootDirs = GradleSettings.getInstance(project).linkedProjectsSettings
            .mapNotNull { it.externalProjectPath }
            .map { Paths.get(it).toAbsolutePath().normalize() }

        return rootDirs
            .filter { abs.startsWith(it) }
            .minByOrNull { it.nameCount }
    }

    override suspend fun afterDaemonStopped(
        daemon: SpotlessDaemonHost,
        reason: String,
    ) {
        if (daemon !is SpotlessDaemonHost.Unix) return
        runCatching {
            val process = daemonProcesses.remove(daemon)
            cleanupGradleDaemonProcess(process)
            val deleted = daemon.workingDirectory.toFile().deleteRecursively()
            if (!deleted && Files.exists(daemon.workingDirectory)) {
                logger.warn("Failed to delete daemon temp directory after stop ($reason): ${daemon.workingDirectory}")
            }
        }.onFailure { error ->
            logger.warn("Failed to cleanup daemon temp directory after stop ($reason): ${daemon.workingDirectory}", error)
        }
    }

}
