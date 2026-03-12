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
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import dev.ghostflyby.mill.project.MillProjectResolverSupport

internal class MillReloadProjectAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.getData(CommonDataKeys.PROJECT) ?: return
        val linkedProjects = linkedProjectPaths(project)

        if (linkedProjects.isEmpty()) {
            notify(
                project = project,
                title = "No linked Mill project",
                content = "Link a Mill project before reloading it.",
            )
            return
        }

        val externalProjectPath = findReloadTargetPath(project, event, linkedProjects)
        if (externalProjectPath == null) {
            notify(
                project = project,
                title = "Cannot determine Mill project to reload",
                content = "Open a file inside the Mill project you want to reload, or keep only one linked Mill project in this window.",
            )
            return
        }

        FileDocumentManager.getInstance().saveAllDocuments()
        ExternalSystemUtil.refreshProject(
            externalProjectPath,
            ImportSpecBuilder(project, MillConstants.systemId)
                .withActivateToolWindowOnStart(true)
                .withCallback(MillImportRefreshCallback(project)),
        )
    }

    override fun update(event: AnActionEvent) {
        val project = event.getData(CommonDataKeys.PROJECT)
        if (project == null) {
            event.presentation.isEnabledAndVisible = false
            return
        }

        val linkedProjects = linkedProjectPaths(project)
        event.presentation.isVisible = linkedProjects.isNotEmpty() || MillProjectResolverSupport.hasMillConfig(project.basePath)
        event.presentation.isEnabled = linkedProjects.isNotEmpty() && findReloadTargetPath(project, event, linkedProjects) != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun linkedProjectPaths(project: Project): List<String> {
        return MillSettings.getInstance(project).linkedProjectsSettings
            .mapNotNull { settings -> settings.externalProjectPath?.takeIf { it.isNotBlank() } }
    }

    private fun findReloadTargetPath(
        project: Project,
        event: AnActionEvent,
        linkedProjectPaths: List<String>,
    ): String? {
        val contextPaths = buildList {
            event.getData(CommonDataKeys.VIRTUAL_FILE)?.path?.let(::add)
            project.basePath?.let(::add)
        }

        contextPaths.firstNotNullOfOrNull { contextPath ->
            MillProjectResolverSupport.findLinkedProjectPathForContext(contextPath, linkedProjectPaths)
        }
            ?.let { return it }

        return linkedProjectPaths.singleOrNull()
    }

    private fun notify(project: Project, title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(MillConstants.notificationGroupId)
            .createNotification(title, content, NotificationType.WARNING)
            .notify(project)
    }
}
