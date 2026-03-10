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

import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.service.project.ExternalSystemProjectResolver

internal class MillProjectResolver : ExternalSystemProjectResolver<MillExecutionSettings> {
    override fun resolveProjectInfo(
        id: ExternalSystemTaskId,
        projectPath: String,
        isPreviewMode: Boolean,
        settings: MillExecutionSettings?,
        listener: ExternalSystemTaskNotificationListener,
    ): DataNode<ProjectData> {
        val progressReporter = MillImportProgressReporter(id, listener)
        progressReporter.started("Importing Mill project")
        return try {
            progressReporter.progress(15, "Resolving Mill project root")
            val root = MillProjectResolverSupport.findProjectRoot(projectPath)

            progressReporter.progress(40, "Building Mill project model")
            val projectData = MillProjectResolverSupport.buildProjectData(root)
            val projectNode = DataNode(ProjectKeys.PROJECT, projectData, null)

            progressReporter.progress(65, "Creating module and content roots")
            val moduleNode = projectNode.createChild(
                ProjectKeys.MODULE,
                MillProjectResolverSupport.buildModuleData(root, projectData.externalName),
            )
            moduleNode.createChild(ProjectKeys.CONTENT_ROOT, MillProjectResolverSupport.buildContentRoot(root))

            progressReporter.progress(80, "Resolving Mill compile classpath")
            val libraryPaths = MillClasspathResolver.resolveBinaryLibraryPaths(root, settings, id, listener)
            libraryPaths.forEach { path ->
                moduleNode.createChild(
                    ProjectKeys.LIBRARY_DEPENDENCY,
                    MillProjectResolverSupport.buildLibraryDependency(moduleNode.data, path),
                )
            }

            progressReporter.progress(90, "Collecting Mill tasks")
            MillProjectResolverSupport.createTaskData(root).forEach { task ->
                projectNode.createChild(ProjectKeys.TASK, task)
            }

            progressReporter.progress(100, "Finalizing Mill import")
            listener.onTaskOutput(
                id,
                buildString {
                    append(
                        "Resolved a basic Mill project model for ${
                            root.fileName?.toString().orEmpty().ifBlank { "Mill" }
                        }.",
                    )
                    if (libraryPaths.isNotEmpty()) {
                        append(" Imported ${libraryPaths.size} external libraries.")
                    }
                    append('\n')
                },
                ProcessOutputType.STDOUT,
            )
            progressReporter.finished("Mill import finished")
            projectNode
        } catch (error: Throwable) {
            progressReporter.failed("Mill import failed", error)
            throw error
        }
    }

    override fun cancelTask(id: ExternalSystemTaskId, listener: ExternalSystemTaskNotificationListener): Boolean = false
}
