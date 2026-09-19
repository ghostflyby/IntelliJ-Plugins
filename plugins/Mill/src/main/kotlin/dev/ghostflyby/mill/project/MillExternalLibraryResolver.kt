/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill.project

import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import dev.ghostflyby.mill.MillImportDebugLogger
import dev.ghostflyby.mill.command.MillCommandLineUtil
import dev.ghostflyby.mill.settings.MillExecutionSettings
import java.nio.file.Path

internal object MillExternalLibraryResolver {
    fun resolveBinaryLibraryPaths(
        module: MillDiscoveredModule,
        settings: MillExecutionSettings?,
        taskId: ExternalSystemTaskId,
        listener: ExternalSystemTaskNotificationListener,
    ): List<Path> {
        val metadataTargets = listOf(
            module.queryTarget("resolvedMvnDeps"),
            module.queryTarget("resolvedIvyDeps"),
        )
        metadataTargets.forEach { showTarget ->
            val result = MillCommandLineUtil.showBinaryPaths(
                projectRoot = module.projectRoot,
                settings = settings,
                showTarget = showTarget,
            )
            if (!result.command.isSuccess) {
                result.command.reportFailure(taskId, listener, "external library resolution", reportFailures = false)
            }
            val paths = result.value
            if (paths.isNotEmpty()) {
                MillImportDebugLogger.info(
                    "Module `${module.targetPrefix}` external libraries came from `$showTarget`: ${
                        MillImportDebugLogger.sample(paths.map(Path::toString))
                    }",
                )
                return paths
            }
        }

        val fallbackTarget = module.queryTarget("compileClasspath")
        val fallbackResult = MillCommandLineUtil.showBinaryPaths(
            projectRoot = module.projectRoot,
            settings = settings,
            showTarget = fallbackTarget,
        )
        if (!fallbackResult.command.isSuccess) {
            fallbackResult.command.reportFailure(taskId, listener, "compile classpath resolution")
        }
        val fallbackPaths = fallbackResult.value
        if (fallbackPaths.isNotEmpty()) {
            MillImportDebugLogger.warn(
                "Module `${module.targetPrefix}` external libraries fell back to compileClasspath: ${
                    MillImportDebugLogger.sample(fallbackPaths.map(Path::toString))
                }",
            )
            listener.onTaskOutput(
                taskId,
                "Mill external libraries for `${module.targetPrefix}` fell back to compileClasspath.\n",
                ProcessOutputType.STDOUT,
            )
        } else {
            MillImportDebugLogger.warn("Module `${module.targetPrefix}` resolved no external libraries from metadata or compileClasspath")
        }
        return fallbackPaths
    }
}
