/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

@file:Suppress("UnstableApiUsage")
// EEL (Path.getEelDescriptor / LocalEelDescriptor) and WSL path APIs (WslPath /
// WSLDistribution.getWslPath) are still Experimental / evolving in 2026.1. EEL provides no public
// Windows drive letter -> /mnt/<drive> mapping; the platform's own implementation
// (TerminalLocalPathTranslator) also combines EEL detection with a WSLDistribution.getWslPath
// fallback. Same pattern here; track platform API changes in this single file.

package dev.ghostflyby.dcevm.wsl

import com.intellij.execution.wsl.WslPath
import com.intellij.platform.eel.provider.LocalEelDescriptor
import com.intellij.platform.eel.provider.getEelDescriptor
import java.nio.file.Path

/**
 * Converts a host-side path into the form visible to the Gradle daemon running inside the
 * environment that hosts [projectPath] (local machine / WSL distribution / ...).
 *
 * - Local project: the host path is returned as-is.
 * - WSL project: host drive paths (e.g. the bundled agent jar under the plugin lib dir) are mapped
 *   to `/mnt/<drive>/...` via [com.intellij.execution.wsl.WSLDistribution.getWslPath]; UNC paths
 *   inside the same distribution are converted to their Linux form.
 * - Other non-local environments (dev containers): no drive mapping exists, the path is returned
 *   unchanged (known limitation; needs an EEL transfer-based approach later).
 */
internal fun toDaemonVisiblePath(projectPath: String, hostPath: Path): String {
    if (Path.of(projectPath).getEelDescriptor() === LocalEelDescriptor) {
        return hostPath.toString()
    }
    val projectWsl = WslPath.parseWindowsUncPath(projectPath) ?: return hostPath.toString()
    val hostWsl = WslPath.parseWindowsUncPath(hostPath.toString())
    if (hostWsl != null && hostWsl.distributionId == projectWsl.distributionId) {
        return hostWsl.linuxPath
    }
    return projectWsl.distribution.getWslPath(hostPath) ?: hostPath.toString()
}
