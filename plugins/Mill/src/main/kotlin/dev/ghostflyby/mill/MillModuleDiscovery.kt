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
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import java.nio.file.Files
import java.nio.file.Path

internal object MillModuleDiscovery {
    fun discoverModules(
        root: Path,
        projectName: String,
        settings: MillExecutionSettings?,
        taskId: ExternalSystemTaskId,
        listener: ExternalSystemTaskNotificationListener,
    ): List<MillDiscoveredModule> {
        val output = try {
            CapturingProcessHandler(createCommandLine(root, settings)).runProcess()
        } catch (_: ExecutionException) {
            listener.onTaskOutput(
                taskId,
                "Mill module discovery skipped because the Mill process could not be started.\n",
                ProcessOutputType.STDERR,
            )
            return fallbackModules(root, projectName)
        }

        if (output.exitCode != 0) {
            val details = output.stderr.ifBlank { output.stdout }.trim()
            listener.onTaskOutput(
                taskId,
                buildString {
                    append("Mill module discovery skipped because `resolve _` failed.")
                    if (details.isNotBlank()) {
                        append('\n')
                        append(details)
                    }
                    append('\n')
                },
                ProcessOutputType.STDERR,
            )
            return fallbackModules(root, projectName)
        }

        val resolvedTargets = parseResolvedTargets(output.stdout)
        val discoveredModules = discoverModulesFromTargets(root, projectName, resolvedTargets)
        return if (discoveredModules.isEmpty()) fallbackModules(root, projectName) else discoveredModules
    }

    internal fun parseResolvedTargets(output: String): List<String> {
        return output.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filterNot { it.startsWith('[') }
            .filterNot { it.startsWith("Loading") }
            .filter { !it.contains(' ') }
            .distinct()
            .toList()
    }

    internal fun discoverModulesFromTargets(
        root: Path,
        projectName: String,
        resolvedTargets: List<String>,
    ): List<MillDiscoveredModule> {
        val candidateModules = linkedMapOf<String, MillDiscoveredModule>()

        resolvedTargets
            .mapNotNull(::targetPrefix)
            .distinct()
            .forEach { prefix ->
                val directory = root.resolve(prefix.replace('.', '/')).normalize()
                if (directory.startsWith(root) && hasModuleContent(directory)) {
                    candidateModules[prefix] = MillDiscoveredModule(
                        displayName = prefix,
                        targetPrefix = prefix,
                        directory = directory,
                        productionModulePrefix = prefix.removeSuffix(".test").takeIf { prefix.endsWith(".test") },
                    )
                }
            }

        val modules = candidateModules.values
            .sortedWith(compareBy<MillDiscoveredModule> { it.directory.nameCount }.thenBy { it.displayName })
            .toMutableList()

        if (hasModuleContent(root) || modules.isEmpty()) {
            modules.add(
                0,
                MillDiscoveredModule(
                    displayName = projectName,
                    targetPrefix = "__",
                    directory = root,
                ),
            )
        }

        return modules.distinctBy { it.directory }
    }

    private fun targetPrefix(target: String): String? {
        val lastDot = target.lastIndexOf('.')
        if (lastDot <= 0) {
            return null
        }

        val prefix = target.substring(0, lastDot)
        return prefix.takeUnless { it == "_" || it == "__" || it.isBlank() }
    }

    private fun createCommandLine(root: Path, settings: MillExecutionSettings?): GeneralCommandLine {
        val executable = settings?.millExecutablePath?.ifBlank { MillConstants.defaultExecutable }
            ?: MillConstants.defaultExecutable
        return GeneralCommandLine(listOf(executable, "resolve", "_"))
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

    private fun fallbackModules(root: Path, projectName: String): List<MillDiscoveredModule> {
        return listOf(MillDiscoveredModule(displayName = projectName, targetPrefix = "__", directory = root))
    }

    private fun hasModuleContent(directory: Path): Boolean {
        if (!Files.isDirectory(directory)) {
            return false
        }

        return MillProjectResolverSupport.moduleContentDirectoryNames.any { Files.isDirectory(directory.resolve(it)) }
    }
}

internal data class MillDiscoveredModule(
    val displayName: String,
    val targetPrefix: String,
    val directory: Path,
    val productionModulePrefix: String? = null,
)
