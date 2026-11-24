/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill.rpc

import dev.ghostflyby.mill.command.MillCommandLineUtil
import dev.ghostflyby.mill.settings.MillExecutableSource
import dev.ghostflyby.mill.settings.MillExecutionTargets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.nio.file.Path

internal class MillBackendRpcApiImpl : MillBackendRpcApi {
    override suspend fun discoverExecutables(projectPath: String): MillExecutableDiscoveryDto {
        return runInterruptible(Dispatchers.IO) {
            val root = Path.of(projectPath)
            MillCommandLineUtil.discoverExecutables(root, MillExecutionTargets.detect(root)).toDto()
        }
    }

    override suspend fun probeExecutable(
        projectPath: String,
        executableSource: String,
        executablePath: String,
    ): MillExecutableProbeDto {
        return runInterruptible(Dispatchers.IO) {
            val root = Path.of(projectPath)
            val source = runCatching { MillExecutableSource.valueOf(executableSource) }
                .getOrDefault(MillExecutableSource.PROJECT_DEFAULT_SCRIPT)
            MillCommandLineUtil.probeExecutable(root, source, executablePath, MillExecutionTargets.detect(root)).toDto()
        }
    }
}
