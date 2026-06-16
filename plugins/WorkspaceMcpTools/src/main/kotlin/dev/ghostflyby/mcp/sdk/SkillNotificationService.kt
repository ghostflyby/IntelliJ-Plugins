/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.ghostflyby.mcp.Bundle
import dev.ghostflyby.mcp.pluginVersion

internal class SkillNotificationActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val settings = service<WorkspaceMcpSdkServerSettings>()
        val currentVersion = pluginVersion
        if (currentVersion == "unknown") return
        if (currentVersion == settings.codexSkillNotifiedVersion) return

        settings.codexSkillNotifiedVersion = currentVersion

        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("Workspace Agent Bridge")
            .createNotification(
                Bundle.message("sdk.skill.notification.title"),
                Bundle.message("sdk.skill.notification.content"),
                NotificationType.INFORMATION,
            )
        notification.addAction(
            NotificationAction.createSimpleExpiring(Bundle.message("sdk.skill.notification.action.dismiss")) {
                notification.expire()
            },
        )
        notification.notify(project)
    }
}
