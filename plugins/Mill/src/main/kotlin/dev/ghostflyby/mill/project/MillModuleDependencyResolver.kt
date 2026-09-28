/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill.project

import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import dev.ghostflyby.mill.command.MillCommandLineUtil
import dev.ghostflyby.mill.settings.MillExecutionSettings

internal object MillModuleDependencyResolver {
    fun resolveDependencyPrefixes(
        module: MillDiscoveredModule,
        discoveredModules: Collection<MillDiscoveredModule>,
        settings: MillExecutionSettings?,
        taskId: ExternalSystemTaskId,
        listener: ExternalSystemTaskNotificationListener,
    ): List<String> {
        if (settings?.useMillMetadataDuringImport == false) {
            return emptyList()
        }
        val knownModules = discoveredModules.associateBy { it.targetPrefix }
        val result = MillCommandLineUtil.showStringValues(
            projectRoot = module.projectRoot,
            settings = settings,
            showTarget = module.queryTarget("moduleDeps"),
        )
        if (!result.command.isSuccess) {
            result.command.reportFailure(taskId, listener, "module dependency resolution", reportFailures = false)
        }
        return result.value.asSequence()
            .map(::normalizeDependencyPrefix)
            .filter(String::isNotBlank)
            .distinct()
            .filter { dependencyPrefix -> dependencyPrefix != module.targetPrefix }
            .filter(knownModules::containsKey)
            .toList()
    }

    internal fun normalizeDependencyPrefix(rawValue: String): String {
        val trimmed = rawValue.trim()
        if (trimmed.isBlank()) {
            return ""
        }

        val normalizedSeparators = trimmed.replace('/', '.')
        return normalizedSeparators.substringBefore('[')
            .removePrefix("_.")
            .removePrefix("__.")
            .trim('.')
    }
}
