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

package dev.ghostflyby.mill

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import java.nio.file.Path

internal const val MillModuleJdkHomeProperty: String = "mill.jdk.home"

internal object MillModuleJdkResolver {
    fun resolve(
        module: MillDiscoveredModule,
        settings: MillExecutionSettings?,
    ): String? {
        val jdkHomePath = resolveJavaHome(
            module = module,
            settings = settings,
        )?.let(MillModuleJdkSupport::normalizeJdkHomePath)

        MillImportDebugLogger.info(
            "Module `${module.targetPrefix}` resolved javaHome=${jdkHomePath ?: "<project-sdk>"}",
        )
        return jdkHomePath
    }

    private fun resolveJavaHome(
        module: MillDiscoveredModule,
        settings: MillExecutionSettings?,
    ): String? {
        val command = MillCommandLineUtil.buildMillCommand(
            projectRoot = module.projectRoot,
            executable = settings?.millExecutablePath ?: MillConstants.defaultExecutable,
            jvmOptionsText = settings?.millJvmOptions.orEmpty(),
            arguments = listOf("${module.targetPrefix}.java", "-XshowSettings:properties", "-version"),
        )
        return try {
            MillImportDebugLogger.info("Running `${command.joinToString(" ")}` in ${module.projectRoot}")
            val output = CapturingProcessHandler(createCommandLine(module.projectRoot, settings, command)).runProcess()
            if (output.exitCode != 0) {
                MillImportDebugLogger.warn(
                    "Module `${module.targetPrefix}` JDK resolution failed exitCode=${output.exitCode} details=${
                        MillImportDebugLogger.trim((output.stderr + "\n" + output.stdout).trim())
                    }",
                )
                null
            } else {
                parseJavaHome(output.stdout + "\n" + output.stderr)
            }
        } catch (_: ExecutionException) {
            MillImportDebugLogger.warn("Mill process could not be started for `${module.targetPrefix}.java -XshowSettings:properties -version`")
            null
        }
    }

    internal fun parseJavaHome(output: String): String? {
        return output.lineSequence()
            .map(String::trim)
            .firstNotNullOfOrNull { line ->
                line.substringAfter("java.home = ", missingDelimiterValue = "")
                    .takeIf(String::isNotBlank)
                    ?: line.substringAfter("java.home=", missingDelimiterValue = "").takeIf(String::isNotBlank)
            }
    }

    private fun createCommandLine(
        root: Path,
        settings: MillExecutionSettings?,
        command: List<String>,
    ): GeneralCommandLine {
        return GeneralCommandLine(command)
            .withWorkingDirectory(root)
            .withEnvironment(settings?.env ?: emptyMap())
            .withParentEnvironmentType(
                if (settings?.isPassParentEnvs != false) {
                    GeneralCommandLine.ParentEnvironmentType.CONSOLE
                } else {
                    GeneralCommandLine.ParentEnvironmentType.NONE
                },
            )
    }
}

internal object MillModuleJdkSupport {
    fun normalizeJdkHomePath(rawValue: String?): String? {
        val pathValue = rawValue?.trim()?.takeIf(String::isNotBlank) ?: return null
        return runCatching { Path.of(pathValue).toAbsolutePath().normalize().toString() }.getOrNull()
    }

    fun createUniqueSdkName(baseName: String, existingNames: Set<String>): String {
        if (baseName !in existingNames) {
            return baseName
        }

        var suffix = 2
        while (true) {
            val candidate = "$baseName ($suffix)"
            if (candidate !in existingNames) {
                return candidate
            }
            suffix += 1
        }
    }
}
