/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

@file:Suppress("UnstableApiUsage")
// EEL (Path.getEelDescriptor / LocalEelDescriptor / EelPathUtils) and WSL path APIs (WslPath /
// WSLDistribution.getWslPath) are still Experimental / evolving in 2026.1. EEL provides no public
// Windows drive letter -> /mnt/<drive> mapping; the platform's own implementation
// (TerminalLocalPathTranslator) also combines EEL detection with a WSLDistribution.getWslPath
// fallback, and the debugger transfers host-only files via EelPathUtils.transferLocalContentToRemote
// (AsyncStacksUtils). Same patterns here; track platform API changes in this single file.

package dev.ghostflyby.dcevm.wsl

import com.intellij.execution.wsl.WslPath
import com.intellij.openapi.diagnostic.Logger
import com.intellij.platform.eel.path.EelPathException
import com.intellij.platform.eel.provider.LocalEelDescriptor
import com.intellij.platform.eel.provider.getEelDescriptor
import com.intellij.platform.eel.provider.utils.EelPathUtils
import java.io.IOException
import java.nio.file.Path

private val logger = Logger.getInstance("dev.ghostflyby.dcevm.wsl")

/**
 * Converts a host-side path into the form visible to the Gradle daemon running inside the
 * environment that hosts [projectPath] (local machine / WSL distribution / dev container).
 *
 * - Local project: the host path is returned as-is.
 * - WSL project: host drive paths (e.g. the bundled agent jar under the plugin lib dir) are mapped
 *   to `/mnt/<drive>/...` via [com.intellij.execution.wsl.WSLDistribution.getWslPath]; UNC paths
 *   inside the same distribution are converted to their Linux form. If the drive mapping is
 *   unavailable, falls back to the EEL transfer below.
 * - Other non-local environments (dev containers): no drive mapping exists — the host file is
 *   transferred into the environment via [EelPathUtils.transferLocalContentToRemote] (cached by
 *   the platform per source path) and the target-side path is returned.
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
        // Drive mapping unavailable — fall through to the EEL transfer below
    }

    return try {
        val remotePath = EelPathUtils.transferLocalContentToRemote(
            hostPath,
            EelPathUtils.TransferTarget.Temporary(projectDescriptor),
        )
        EelPathUtils.renderAsEelPath(remotePath)
    } catch (e: IOException) {
        logger.warn("Failed to transfer $hostPath into the environment of $projectPath", e)
        hostPath.toString()
    } catch (e: EelPathException) {
        logger.warn("Failed to render target-side path for $hostPath in $projectPath", e)
        hostPath.toString()
    }
}
