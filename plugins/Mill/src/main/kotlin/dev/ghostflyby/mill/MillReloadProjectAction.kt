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
import com.intellij.openapi.project.DumbAwareAction

internal class MillReloadProjectAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.getData(CommonDataKeys.PROJECT) ?: return
        val linkedProjects = MillSettings.getInstance(project).linkedProjectsSettings
            .mapNotNull { settings -> settings.externalProjectPath?.takeIf { it.isNotBlank() } }

        if (linkedProjects.isEmpty()) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(MillConstants.notificationGroupId)
                .createNotification(
                    "No linked Mill project",
                    "Link a Mill project before reloading it.",
                    NotificationType.WARNING,
                )
                .notify(project)
            return
        }

        linkedProjects.forEach { externalProjectPath ->
            ExternalSystemUtil.refreshProject(
                externalProjectPath,
                ImportSpecBuilder(project, MillConstants.systemId)
                    .withActivateToolWindowOnStart(true)
                    .withCallback(MillImportRefreshCallback(project)),
            )
        }
    }

    override fun update(event: AnActionEvent) {
        val project = event.getData(CommonDataKeys.PROJECT)
        val hasLinkedProjects = project != null &&
            MillSettings.getInstance(project).linkedProjectsSettings.any { settings ->
                !settings.externalProjectPath.isNullOrBlank()
            }
        event.presentation.isEnabledAndVisible = hasLinkedProjects
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
