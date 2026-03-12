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

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.util.execution.ParametersListUtil
import dev.ghostflyby.mill.MillConstants
import dev.ghostflyby.mill.MillExecutionSettings
import dev.ghostflyby.mill.MillImportDebugLogger
import java.nio.file.Files
import java.nio.file.Path

internal object MillCommandLineUtil {
    fun buildMillCommand(
        projectRoot: Path,
        executable: String,
        jvmOptionsText: String,
        arguments: List<String>,
    ): List<String> {
        val resolvedExecutable = resolveExecutable(projectRoot, executable)
        return buildList {
            add(resolvedExecutable)
            addAll(parseOptions(jvmOptionsText))
            addAll(arguments.filter(String::isNotBlank))
        }
    }

    internal fun resolveExecutable(projectRoot: Path, configuredExecutable: String): String {
        val rawExecutable = configuredExecutable.trim()
        if (rawExecutable.isNotEmpty()) {
            val configuredPath = runCatching { Path.of(rawExecutable) }.getOrNull()
            if (configuredPath != null) {
                if (configuredPath.isAbsolute) {
                    val resolved = configuredPath.normalize().toString()
                    MillImportDebugLogger.info("Using configured Mill executable `$resolved`")
                    return resolved
                }
                val projectRelativePath = projectRoot.resolve(configuredPath).normalize()
                if (Files.isRegularFile(projectRelativePath)) {
                    val resolved = projectRelativePath.toString()
                    MillImportDebugLogger.info("Using project-relative Mill executable `$resolved`")
                    return resolved
                }
            }
            if (rawExecutable != MillConstants.defaultExecutable) {
                MillImportDebugLogger.info("Using configured Mill executable command `$rawExecutable`")
                return rawExecutable
            }
        }

        discoverWrapper(projectRoot)?.let { wrapper ->
            val resolved = wrapper.toString()
            MillImportDebugLogger.info("Using detected Mill wrapper `$resolved`")
            return resolved
        }
        val resolved = rawExecutable.ifBlank { MillConstants.defaultExecutable }
        MillImportDebugLogger.info("Falling back to Mill executable command `$resolved`")
        return resolved
    }

    internal fun parseOptions(rawValue: String): List<String> {
        return ParametersListUtil.parse(rawValue.trim(), false, true)
            .map(String::trim)
            .filter(String::isNotEmpty)
    }

    internal fun createCommandLine(
        projectRoot: Path,
        settings: MillExecutionSettings?,
        arguments: List<String>,
    ): GeneralCommandLine {
        val command = buildMillCommand(
            projectRoot = projectRoot,
            executable = settings?.millExecutablePath ?: MillConstants.defaultExecutable,
            jvmOptionsText = settings?.millJvmOptions.orEmpty(),
            arguments = arguments,
        )
        return GeneralCommandLine(command)
            .withWorkingDirectory(projectRoot)
            .withEnvironment(settings?.env ?: emptyMap())
            .withParentEnvironmentType(
                if (settings?.isPassParentEnvs != false) {
                    GeneralCommandLine.ParentEnvironmentType.CONSOLE
                } else {
                    GeneralCommandLine.ParentEnvironmentType.NONE
                },
            )
    }

    internal fun runCommand(
        projectRoot: Path,
        settings: MillExecutionSettings?,
        arguments: List<String>,
    ): ProcessOutput = CapturingProcessHandler(createCommandLine(projectRoot, settings, arguments)).runProcess()

    private fun discoverWrapper(projectRoot: Path): Path? {
        val candidates = buildList {
            add(projectRoot.resolve(MillConstants.wrapperScriptName))
            add(projectRoot.resolve(MillConstants.wrapperBatchName))
        }
        return candidates.firstOrNull(Files::isRegularFile)
    }
}
