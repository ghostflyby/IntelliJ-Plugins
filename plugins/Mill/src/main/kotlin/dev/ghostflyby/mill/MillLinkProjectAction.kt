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
import com.intellij.openapi.externalSystem.service.project.trusted.ExternalSystemTrustedProjectDialog
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import dev.ghostflyby.mill.project.MillProjectResolverSupport
import dev.ghostflyby.mill.settings.MillProjectSettings
import dev.ghostflyby.mill.settings.MillSettings

internal class MillLinkProjectAction : DumbAwareAction() {
    init {
        templatePresentation.text = Bundle.message("action.link.project.text")
        templatePresentation.description = Bundle.message("action.link.project.description")
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.getData(CommonDataKeys.PROJECT) ?: return
        val externalProjectPath = findLinkTargetPath(project, event)
        if (externalProjectPath == null) {
            notify(
                project = project,
                title = Bundle.message("action.link.project.notify.missing.title"),
                content = Bundle.message("action.link.project.notify.missing.content"),
            )
            return
        }
        if (MillSettings.getInstance(project).getLinkedProjectSettings(externalProjectPath) != null) {
            notify(
                project = project,
                title = Bundle.message("action.link.project.notify.already.linked.title"),
                content = externalProjectPath,
            )
            return
        }
        if (!ExternalSystemTrustedProjectDialog.confirmLoadingUntrustedProject(project, MillConstants.systemId)) {
            return
        }

        val settings = MillProjectSettings().apply {
            this.externalProjectPath = externalProjectPath
        }
        ExternalSystemUtil.linkExternalProject(
            settings,
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

        val externalProjectPath = findLinkTargetPath(project, event)
        event.presentation.isVisible = externalProjectPath != null || MillProjectResolverSupport.hasMillConfig(project.basePath)
        event.presentation.isEnabled = externalProjectPath != null &&
            MillSettings.getInstance(project).getLinkedProjectSettings(externalProjectPath) == null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun findLinkTargetPath(project: Project, event: AnActionEvent): String? {
        val contextPaths = buildList {
            event.getData(CommonDataKeys.VIRTUAL_FILE)?.path?.let(::add)
            project.basePath?.let(::add)
        }

        return contextPaths.firstNotNullOfOrNull { contextPath ->
            runCatching { MillProjectResolverSupport.findProjectRoot(contextPath).toString() }.getOrNull()
        }
    }

    private fun notify(project: Project, title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(MillConstants.notificationGroupId)
            .createNotification(title, content, NotificationType.INFORMATION)
            .notify(project)
    }
}
