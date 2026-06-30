/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill.project

import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import dev.ghostflyby.mill.MillConstants
import dev.ghostflyby.mill.MillImportDebugLogger
import dev.ghostflyby.mill.command.MillCommandLineUtil
import dev.ghostflyby.mill.settings.MillExecutionSettings
import java.nio.file.Files
import java.nio.file.Path

internal object MillModuleDiscovery {
    fun resolveTargets(
        root: Path,
        settings: MillExecutionSettings?,
        taskId: ExternalSystemTaskId,
        listener: ExternalSystemTaskNotificationListener,
    ): List<String> {
        MillImportDebugLogger.info("Running `mill show resolve ${MillConstants.moduleDiscoveryQuery}` in $root")
        val result = MillCommandLineUtil.resolveTargets(root, settings)
        if (!result.command.isSuccess) {
            MillImportDebugLogger.warn(
                "`mill ${result.command.invocation}` failed in $root exitCode=${result.command.exitCode} " +
                    "details=${MillImportDebugLogger.trim(result.command.failureDetails)}",
            )
            result.command.reportFailure(taskId, listener, "module discovery")
            return emptyList()
        }

        MillImportDebugLogger.warn(
            "`mill ${result.command.invocation}` produced ${result.value.size} target(s): " +
                "${MillImportDebugLogger.sample(result.value)}; " +
                "raw=${MillImportDebugLogger.trim(result.command.stdout, 800)}",
        )
        return result.value
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
