/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

@file:Suppress("UnstableApiUsage")
// EEL (Path.getEelDescriptor / LocalEelDescriptor / EelPath / asNioPath / EelPathBoundDescriptor)
// and WSL path APIs (WslPath / WSLDistribution.getWslPath) are still Experimental / evolving in
// 2026.1. EEL provides no public Windows drive letter -> /mnt/<drive> mapping; the platform's own
// implementation (TerminalLocalPathTranslator) also combines EEL detection with a
// WSLDistribution.getWslPath fallback. Same pattern here; track platform API changes in this file.

package dev.ghostflyby.dcevm.wsl

import com.intellij.execution.wsl.WslPath
import com.intellij.openapi.diagnostic.Logger
import com.intellij.platform.eel.EelDescriptor
import com.intellij.platform.eel.EelPathBoundDescriptor
import com.intellij.platform.eel.path.EelPath
import com.intellij.platform.eel.path.EelPathException
import com.intellij.platform.eel.provider.LocalEelDescriptor
import com.intellij.platform.eel.provider.asNioPath
import com.intellij.platform.eel.provider.getEelDescriptor
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val logger = Logger.getInstance("dev.ghostflyby.dcevm.wsl")

private val transferredPaths = ConcurrentHashMap<String, String>()

/**
 * Converts a host-side path into the form visible to the Gradle daemon running inside the
 * environment that hosts [projectPath] (local machine / WSL distribution / dev container).
 *
 * - Local project: the host path is returned as-is.
 * - WSL project: host drive paths (e.g. the bundled agent jar under the plugin lib dir) are mapped
 *   to `/mnt/<drive>/...` via [com.intellij.execution.wsl.WSLDistribution.getWslPath]; UNC paths
 *   inside the same distribution are converted to their Linux form. If the drive mapping is
 *   unavailable, falls back to the routed-NIO transfer below.
 * - Other non-local environments (dev containers): no drive mapping exists — the host file is
 *   copied into the environment through its routed NIO filesystem (see [transferIntoTarget]) and
 *   the target-side path is returned.
 *
 * Must be called off the EDT (the transfer and WSL resolution block on background I/O).
 */
internal fun toDaemonVisiblePath(projectPath: String, hostPath: Path): String {
    val projectDescriptor = Path.of(projectPath).getEelDescriptor()
    if (projectDescriptor === LocalEelDescriptor) {
        return hostPath.toString()
    }

    val projectWsl = WslPath.parseWindowsUncPath(projectPath)
    if (projectWsl != null) {
        val hostWsl = WslPath.parseWindowsUncPath(hostPath.toString())
        if (hostWsl != null && hostWsl.distributionId == projectWsl.distributionId) {
            return hostWsl.linuxPath
        }
        projectWsl.distribution.getWslPath(hostPath)?.let { return it }
        // Drive mapping unavailable — fall through to the routed-NIO transfer below
    }

    return transferIntoTarget(projectDescriptor, hostPath, projectPath)
}

/**
 * Copies [hostPath] into the environment of [projectDescriptor] via its routed NIO filesystem
 * (`EelPath.asNioPath` + plain `java.nio.file.Files`), returning the environment-side path string.
 * Transfers are cached per source path; the cache revalidates file existence so environment
 * restarts trigger a re-transfer.
 */
private fun transferIntoTarget(projectDescriptor: EelDescriptor, hostPath: Path, projectPath: String): String {
    val cacheKey = hostPath.toString() + "|" + descriptorKey(projectDescriptor)
    transferredPaths[cacheKey]?.let { cached ->
        val exists = runCatching { Files.exists(EelPath.parse(cached, projectDescriptor).asNioPath()) }
        if (exists.getOrNull() == true) {
            return cached
        }
        transferredPaths.remove(cacheKey, cached)
    }

    return try {
        val targetFile = EelPath.parse("/tmp/ghostflyby-dcevm/" + UUID.randomUUID(), projectDescriptor)
            .resolve(hostPath.fileName.toString())
        val routedFile = targetFile.asNioPath()
        Files.createDirectories(routedFile.parent)
        Files.write(routedFile, Files.readAllBytes(hostPath))
        logger.info("Transferred $hostPath into $projectPath environment as $routedFile")
        val targetString = targetFile.toString()
        transferredPaths[cacheKey] = targetString
        targetString
    } catch (e: IOException) {
        logger.warn("Failed to transfer $hostPath into the environment of $projectPath", e)
        hostPath.toString()
    } catch (e: EelPathException) {
        logger.warn("Failed to build target-side path for $hostPath in $projectPath", e)
        hostPath.toString()
    } catch (e: IllegalArgumentException) {
        logger.warn("No routed filesystem available for $projectPath", e)
        hostPath.toString()
    }
}

private fun descriptorKey(descriptor: EelDescriptor): String =
    (descriptor as? EelPathBoundDescriptor)?.rootPath?.toString() ?: descriptor.name
