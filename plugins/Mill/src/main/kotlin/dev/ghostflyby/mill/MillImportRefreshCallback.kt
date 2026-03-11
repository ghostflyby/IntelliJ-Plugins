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

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.Key
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.service.project.ExternalProjectRefreshCallback
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project

internal class MillImportRefreshCallback(
    private val project: Project,
) : ExternalProjectRefreshCallback {
    override fun onSuccess(externalTaskId: ExternalSystemTaskId, externalProject: DataNode<ProjectData>?) {
        val resolvedModuleCount = externalProject.count(ProjectKeys.MODULE)
        val resolvedLibraryCount = externalProject.count(ProjectKeys.LIBRARY_DEPENDENCY)
        ApplicationManager.getApplication().invokeLater(
            {
                if (project.isDisposed) return@invokeLater

                val moduleManager = ModuleManager.getInstance(project)
                val moduleCountBefore = moduleManager.modules.size
                if (externalProject != null && resolvedModuleCount > 0 && moduleCountBefore == 0) {
                    ProjectDataManager.getInstance().importData(externalProject, project)
                }
                val moduleCountAfter = moduleManager.modules.size

                val content = buildString {
                    append("Resolved ")
                    append(resolvedModuleCount)
                    append(" module(s) and ")
                    append(resolvedLibraryCount)
                    append(" library dependenc")
                    append(if (resolvedLibraryCount == 1) "y" else "ies")
                    append(". IDE currently has ")
                    append(moduleCountAfter)
                    append(" module(s).")
                }
                thisLogger().warn("Mill import result: $content")
                NotificationGroupManager.getInstance()
                    .getNotificationGroup(MillConstants.notificationGroupId)
                    .createNotification("Mill import finished", content, NotificationType.INFORMATION)
                    .notify(project)
            },
            project.disposed,
        )
    }

    override fun onFailure(externalTaskId: ExternalSystemTaskId, errorMessage: String, errorDetails: String?) {
        val content = buildString {
            append(errorMessage)
            if (!errorDetails.isNullOrBlank()) {
                append('\n')
                append(errorDetails)
            }
        }
        thisLogger().warn("Mill import failed: $content")
        NotificationGroupManager.getInstance()
            .getNotificationGroup(MillConstants.notificationGroupId)
            .createNotification("Mill import failed", content, NotificationType.ERROR)
            .notify(project)
    }

    private fun DataNode<ProjectData>?.count(key: Key<*>): Int {
        if (this == null) return 0
        var count = 0
        fun visit(node: DataNode<*>) {
            if (node.key == key) {
                count += 1
            }
            node.children.forEach(::visit)
        }
        visit(this)
        return count
    }
}
