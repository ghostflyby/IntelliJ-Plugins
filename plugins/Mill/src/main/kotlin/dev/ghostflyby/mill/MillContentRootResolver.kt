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

import com.intellij.openapi.externalSystem.model.project.ContentRootData
import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import java.nio.file.Files
import java.nio.file.Path

internal object MillContentRootResolver {
    fun buildContentRoot(
        module: MillDiscoveredModule,
        settings: MillExecutionSettings?,
        taskId: ExternalSystemTaskId,
        listener: ExternalSystemTaskNotificationListener,
    ): ContentRootData {
        if (settings?.useMillMetadataDuringImport == false) {
            return MillProjectResolverSupport.buildContentRoot(module.directory)
        }
        val metadataRoots = resolveMetadataRoots(module, settings, taskId, listener)
        return if (metadataRoots.isEmpty()) {
            MillProjectResolverSupport.buildContentRoot(module.directory)
        } else {
            MillProjectResolverSupport.buildContentRoot(module.directory, metadataRoots)
        }
    }

    private fun resolveMetadataRoots(
        module: MillDiscoveredModule,
        settings: MillExecutionSettings?,
        taskId: ExternalSystemTaskId,
        listener: ExternalSystemTaskNotificationListener,
    ): List<Pair<ExternalSystemSourceType, Path>> {
        val sourceTargets = sourceTargetsFor(module)
        return sourceTargets.entries
            .asSequence()
            .flatMap { (targetName, sourceType) ->
                MillShowTargetPathResolver.resolvePaths(
                    root = module.projectRoot,
                    settings = settings,
                    taskId = taskId,
                    listener = listener,
                    showTarget = "${module.targetPrefix}.$targetName",
                    failureContext = "content root resolution",
                    reportFailures = false,
                ).asSequence().map { sourceType to it }
            }
            .filter { (_, path) -> Files.isDirectory(path) }
            .filter { (_, path) -> path.startsWith(module.directory) }
            .distinct()
            .toList()
    }

    private fun sourceTargetsFor(module: MillDiscoveredModule): Map<String, ExternalSystemSourceType> {
        val isTestModule = module.isTestModule
        return linkedMapOf(
            "sources" to if (isTestModule) ExternalSystemSourceType.TEST else ExternalSystemSourceType.SOURCE,
            "resources" to if (isTestModule) ExternalSystemSourceType.TEST_RESOURCE else ExternalSystemSourceType.RESOURCE,
            "generatedSources" to if (isTestModule) ExternalSystemSourceType.TEST_GENERATED else ExternalSystemSourceType.SOURCE_GENERATED,
            "generatedResources" to if (isTestModule) ExternalSystemSourceType.TEST_RESOURCE_GENERATED else ExternalSystemSourceType.RESOURCE_GENERATED,
        )
    }
}
