/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * This file is part of IntelliJ-Plugins by ghostflyby
 *
 * IntelliJ-Plugins by ghostflyby is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see
 * <https://www.gnu.org/licenses/>.
 */

package dev.ghostflyby.mill.sdk

import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import dev.ghostflyby.mill.MillExecutionSettings
import dev.ghostflyby.mill.MillImportDebugLogger
import dev.ghostflyby.mill.MillScalaSdkData
import dev.ghostflyby.mill.command.MillCommandLineUtil
import dev.ghostflyby.mill.project.MillDiscoveredModule

internal object MillScalaSdkResolver {
    fun resolve(
        module: MillDiscoveredModule,
        settings: MillExecutionSettings?,
        taskId: ExternalSystemTaskId,
        listener: ExternalSystemTaskNotificationListener,
    ): MillScalaSdkData? {
        val scalaVersionResult = MillCommandLineUtil.showStringValue(
            projectRoot = module.projectRoot,
            settings = settings,
            showTarget = module.queryTarget("scalaVersion"),
        )
        if (!scalaVersionResult.command.isSuccess) {
            scalaVersionResult.command.reportFailure(taskId, listener, "Scala SDK resolution", reportFailures = false)
        }
        val scalaVersion = scalaVersionResult.value ?: run {
            MillImportDebugLogger.info("Module `${module.targetPrefix}` has no scalaVersion metadata")
            return null
        }

        val scalacClasspath = resolveBinaryPaths(module, settings, taskId, listener, "scalaCompilerClasspath")
        if (scalacClasspath.isEmpty()) {
            MillImportDebugLogger.info("Module `${module.targetPrefix}` has scalaVersion=$scalaVersion but empty scalaCompilerClasspath")
            listener.onTaskOutput(
                taskId,
                "Mill Scala SDK for `${module.targetPrefix}` skipped because scalaCompilerClasspath is empty.\n",
                ProcessOutputType.STDOUT,
            )
            return null
        }

        val scaladocClasspath = resolveBinaryPaths(module, settings, taskId, listener, "scalaDocClasspath")
        val replClasspath = resolveBinaryPaths(module, settings, taskId, listener, "ammoniteReplClasspath")
        MillImportDebugLogger.info(
            "Module `${module.targetPrefix}` prepared Scala SDK version=$scalaVersion " +
                "scalac=${scalacClasspath.size} scaladoc=${scaladocClasspath.size} repl=${replClasspath.size}",
        )

        return MillScalaSdkData(
            scalaVersion = scalaVersion,
            scalacClasspath = scalacClasspath.map { it.toString() },
            scaladocClasspath = scaladocClasspath.map { it.toString() },
            replClasspath = replClasspath.map { it.toString() },
        )
    }

    private fun resolveBinaryPaths(
        module: MillDiscoveredModule,
        settings: MillExecutionSettings?,
        taskId: ExternalSystemTaskId,
        listener: ExternalSystemTaskNotificationListener,
        suffix: String,
    ): List<java.nio.file.Path> {
        val result = MillCommandLineUtil.showBinaryPaths(
            projectRoot = module.projectRoot,
            settings = settings,
            showTarget = module.queryTarget(suffix),
        )
        if (!result.command.isSuccess) {
            result.command.reportFailure(taskId, listener, "Scala SDK resolution", reportFailures = false)
        }
        return result.value
    }
}
