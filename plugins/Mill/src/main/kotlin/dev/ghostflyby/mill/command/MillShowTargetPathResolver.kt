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

package dev.ghostflyby.mill.command

import com.intellij.execution.ExecutionException
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import dev.ghostflyby.mill.MillExecutionSettings
import dev.ghostflyby.mill.MillImportDebugLogger
import dev.ghostflyby.mill.output.MillShowJsonSupport
import java.nio.file.Path

internal object MillShowTargetPathResolver {
    fun resolveStringValues(
        root: Path,
        settings: MillExecutionSettings?,
        taskId: ExternalSystemTaskId,
        listener: ExternalSystemTaskNotificationListener,
        showTarget: String,
        failureContext: String,
        reportFailures: Boolean = true,
    ): List<String> {
        val output = runShowTarget(root, settings, taskId, listener, showTarget, failureContext, reportFailures) ?: return emptyList()
        val values = MillShowJsonSupport.parseStringList(output.stdout)?.values.orEmpty()
            .map(::normalizeValue)
            .filter(String::isNotBlank)
        MillImportDebugLogger.info("`mill show $showTarget` parsed ${values.size} string value(s): ${MillImportDebugLogger.sample(values)}")
        return values
    }

    fun resolveStringValue(
        root: Path,
        settings: MillExecutionSettings?,
        taskId: ExternalSystemTaskId,
        listener: ExternalSystemTaskNotificationListener,
        showTarget: String,
        failureContext: String,
        reportFailures: Boolean = true,
    ): String? {
        val output = runShowTarget(root, settings, taskId, listener, showTarget, failureContext, reportFailures) ?: return null
        val value = MillShowJsonSupport.parseStringValue(output.stdout)?.value?.let(::normalizeValue)
        MillImportDebugLogger.info("`mill show $showTarget` parsed single value=${value ?: "<null>"}")
        return value
    }

    fun resolvePaths(
        root: Path,
        settings: MillExecutionSettings?,
        taskId: ExternalSystemTaskId,
        listener: ExternalSystemTaskNotificationListener,
        showTarget: String,
        failureContext: String,
        reportFailures: Boolean = true,
    ): List<Path> {
        val values = resolveStringValues(root, settings, taskId, listener, showTarget, failureContext, reportFailures)
        val paths = values
            .asSequence()
            .mapNotNull { rawPath -> runCatching { Path.of(rawPath) }.getOrNull() }
            .map(Path::toAbsolutePath)
            .map(Path::normalize)
            .distinct()
            .toList()
        MillImportDebugLogger.info("`mill show $showTarget` parsed ${paths.size} path(s): ${MillImportDebugLogger.sample(paths.map(Path::toString))}")
        return paths
    }

    internal fun parsePathList(output: String): List<String> = MillShowJsonSupport.parseStringList(output)?.values
        .orEmpty()
        .map(::normalizeValue)

    internal fun parseStringList(output: String): List<String> = parsePathList(output)

    internal fun parseSingleStringValue(output: String): String? = MillShowJsonSupport.parseStringValue(output)?.value
        ?.let(::normalizeValue)

    private fun runShowTarget(
        root: Path,
        settings: MillExecutionSettings?,
        taskId: ExternalSystemTaskId,
        listener: ExternalSystemTaskNotificationListener,
        showTarget: String,
        failureContext: String,
        reportFailures: Boolean,
    ) = try {
        MillImportDebugLogger.info("Running `mill show $showTarget` in $root")
        val output = MillCommandLineUtil.runCommand(root, settings, listOf("show", showTarget))
        if (output.exitCode != 0) {
            MillImportDebugLogger.warn(
                "`mill show $showTarget` failed in $root exitCode=${output.exitCode} details=${
                    MillImportDebugLogger.trim(output.stderr.ifBlank { output.stdout })
                }",
            )
            if (reportFailures) {
                val details = output.stderr.ifBlank { output.stdout }.trim()
                listener.onTaskOutput(
                    taskId,
                    buildString {
                        append("Mill $failureContext skipped because `show $showTarget` failed.")
                        if (details.isNotBlank()) {
                            append('\n')
                            append(details)
                        }
                        append('\n')
                    },
                    ProcessOutputType.STDERR,
                )
            }
            null
        } else {
            MillImportDebugLogger.info("`mill show $showTarget` succeeded in $root")
            output
        }
    } catch (_: ExecutionException) {
        MillImportDebugLogger.warn("Mill process could not be started for `show $showTarget` in $root")
        if (reportFailures) {
            listener.onTaskOutput(
                taskId,
                "Mill $failureContext skipped because the Mill process could not be started for `$showTarget`.\n",
                ProcessOutputType.STDERR,
            )
        }
        null
    }

    private fun normalizeValue(value: String): String {
        if (value.startsWith("/") || windowsPathPattern.matches(value)) {
            return value
        }

        var candidate = value
        while (true) {
            val separatorIndex = candidate.indexOf(':')
            if (separatorIndex < 0 || separatorIndex == candidate.lastIndex) {
                return value
            }

            candidate = candidate.substring(separatorIndex + 1)
            if (candidate.startsWith("/") || windowsPathPattern.matches(candidate)) {
                return candidate
            }
        }
    }

    private val windowsPathPattern = Regex("""^[A-Za-z]:[\\/].*""")
}
