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

import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import java.nio.file.Path

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
        return MillShowTargetPathResolver.resolveStringValues(
            root = module.projectRoot,
            settings = settings,
            taskId = taskId,
            listener = listener,
            showTarget = module.queryTarget("moduleDeps"),
            failureContext = "module dependency resolution",
            reportFailures = false,
        ).asSequence()
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
