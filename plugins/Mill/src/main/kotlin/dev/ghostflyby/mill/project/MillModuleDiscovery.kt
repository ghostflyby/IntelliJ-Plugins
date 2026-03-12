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

package dev.ghostflyby.mill.project

import com.intellij.execution.ExecutionException
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import dev.ghostflyby.mill.MillConstants
import dev.ghostflyby.mill.MillExecutionSettings
import dev.ghostflyby.mill.MillImportDebugLogger
import dev.ghostflyby.mill.command.MillCommandLineUtil
import java.nio.file.Files
import java.nio.file.Path

internal object MillModuleDiscovery {
    fun resolveTargets(
        root: Path,
        settings: MillExecutionSettings?,
        taskId: ExternalSystemTaskId,
        listener: ExternalSystemTaskNotificationListener,
    ): List<String> {
        MillImportDebugLogger.info("Running `mill resolve ${MillConstants.moduleDiscoveryQuery}` in $root")
        val output = try {
            MillCommandLineUtil.runCommand(root, settings, listOf("resolve", MillConstants.moduleDiscoveryQuery))
        } catch (_: ExecutionException) {
            MillImportDebugLogger.warn(
                "Mill process could not be started for `resolve ${MillConstants.moduleDiscoveryQuery}` in $root",
            )
            listener.onTaskOutput(
                taskId,
                "Mill module discovery skipped because the Mill process could not be started.\n",
                ProcessOutputType.STDERR,
            )
            return emptyList()
        }

        if (output.exitCode != 0) {
            val details = output.stderr.ifBlank { output.stdout }.trim()
            MillImportDebugLogger.warn(
                "`mill resolve ${MillConstants.moduleDiscoveryQuery}` failed in $root exitCode=${output.exitCode} " +
                    "details=${MillImportDebugLogger.trim(details)}",
            )
            listener.onTaskOutput(
                taskId,
                buildString {
                    append(
                        "Mill module discovery skipped because `resolve ${MillConstants.moduleDiscoveryQuery}` failed.",
                    )
                    if (details.isNotBlank()) {
                        append('\n')
                        append(details)
                    }
                    append('\n')
                },
                ProcessOutputType.STDERR,
            )
            return emptyList()
        }

        val targets = parseResolvedTargets(output.stdout)
        MillImportDebugLogger.warn(
            "`mill resolve ${MillConstants.moduleDiscoveryQuery}` produced ${targets.size} target(s): " +
                "${MillImportDebugLogger.sample(targets)}; " +
                "raw=${MillImportDebugLogger.trim(output.stdout, 800)}",
        )
        return targets
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
        val targetsByPrefix = resolvedTargets.groupBy(::targetPrefix)

        resolvedTargets
            .mapNotNull(::targetPrefix)
            .filter { prefix -> isModuleLikePrefix(prefix, targetsByPrefix[prefix].orEmpty()) }
            .distinct()
            .forEach { prefix ->
                candidateModules[prefix] = MillDiscoveredModule(
                    displayName = prefix,
                    targetPrefix = prefix,
                    projectRoot = root,
                    directory = guessModuleDirectory(root, prefix),
                    productionModulePrefix = prefix.removeSuffix(".test").takeIf { prefix.endsWith(".test") },
                )
            }

        val modules = candidateModules.values
            .sortedWith(compareBy<MillDiscoveredModule> { it.directory.nameCount }.thenBy { it.displayName })
            .toMutableList()

        if (hasModuleContent(root) || modules.isEmpty()) {
            modules.add(
                0,
                MillDiscoveredModule(
                    displayName = projectName,
                    targetPrefix = MillConstants.rootModulePrefix,
                    projectRoot = root,
                    directory = root,
                ),
            )
        }

        val distinctModules = modules.distinctBy { it.targetPrefix }
        MillImportDebugLogger.info(
            "Module discovery mapped ${distinctModules.size} module(s): ${
                MillImportDebugLogger.sample(distinctModules.map { "${it.targetPrefix} -> ${it.directory}" })
            }",
        )
        return distinctModules
    }

    private fun targetPrefix(target: String): String? {
        val lastDot = target.lastIndexOf('.')
        if (lastDot <= 0) {
            return null
        }

        val prefix = target.substring(0, lastDot)
        return prefix.takeUnless { it == "_" || it == "__" || it.isBlank() }
    }

    private fun isModuleLikePrefix(prefix: String, targetsForPrefix: List<String>): Boolean {
        if (prefix.isBlank() || prefix == "selective") {
            return false
        }
        return targetsForPrefix.any { target ->
            when (target.substringAfterLast('.', missingDelimiterValue = target)) {
                "compile",
                "test",
                "assembly",
                "jar",
                "sources",
                "resources",
                "generatedSources",
                "generatedResources",
                "compileClasspath",
                "resolvedMvnDeps",
                "resolvedIvyDeps",
                "scalaVersion",
                "scalaCompilerClasspath",
                -> true

                else -> false
            }
        }
    }

    private fun guessModuleDirectory(root: Path, targetPrefix: String): Path {
        val candidate = root.resolve(targetPrefix.replace('.', '/')).normalize()
        return if (candidate.startsWith(root)) candidate else root
    }

    private fun hasModuleContent(directory: Path): Boolean {
        if (!Files.isDirectory(directory)) {
            return false
        }

        return MillProjectResolverSupport.moduleContentDirectoryNames.any { Files.isDirectory(directory.resolve(it)) }
    }
}
