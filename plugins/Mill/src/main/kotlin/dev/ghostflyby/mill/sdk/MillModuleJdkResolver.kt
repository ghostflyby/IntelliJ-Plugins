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

import com.intellij.execution.ExecutionException
import dev.ghostflyby.mill.MillExecutionSettings
import dev.ghostflyby.mill.MillImportDebugLogger
import dev.ghostflyby.mill.command.MillCommandLineUtil
import dev.ghostflyby.mill.project.MillDiscoveredModule

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
        val arguments = listOf(module.queryTarget("java"), "-XshowSettings:properties", "-version")
        return try {
            MillImportDebugLogger.info(
                "Running `${MillCommandLineUtil.createCommandLine(module.projectRoot, settings, arguments).commandLineString}` in ${module.projectRoot}",
            )
            val output = MillCommandLineUtil.runCommand(module.projectRoot, settings, arguments)
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
            MillImportDebugLogger.warn("Mill process could not be started for `${module.queryTarget("java")} -XshowSettings:properties -version`")
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
}
