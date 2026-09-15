/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill.sdk

import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import dev.ghostflyby.mill.MillImportDebugLogger
import dev.ghostflyby.mill.MillScalaSdkData
import dev.ghostflyby.mill.command.MillCommandLineUtil
import dev.ghostflyby.mill.project.MillDiscoveredModule
import dev.ghostflyby.mill.settings.MillExecutionSettings

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
