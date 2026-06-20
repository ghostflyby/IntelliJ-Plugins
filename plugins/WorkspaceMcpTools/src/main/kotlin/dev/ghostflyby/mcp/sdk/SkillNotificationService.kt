/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.ide.BrowserUtil
import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.ghostflyby.mcp.PluginInfo
import dev.ghostflyby.mcp.message
import dev.ghostflyby.mcp.pluginVersion
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.div

internal class SkillNotificationActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!shouldRunSkillNotificationActivity()) return

        val settings = service<WorkspaceMcpSdkServerSettings>()
        val currentVersion = pluginVersion
        if (!shouldNotifySkill(currentVersion, settings.previousVersion)) return

        settings.previousVersion = currentVersion

        val localSkillPath = bundledSkillPath()
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("Workspace Agent Bridge")
            .createNotification(
                message("sdk.skill.notification.title"),
                message("sdk.skill.notification.content"),
                NotificationType.INFORMATION,
            )
        notification.addSkillLocationActions(localSkillPath)
        notification.addAction(
            NotificationAction.create(message("sdk.skill.notification.action.dismiss")) {
                notification.expire()
            },
        )
        notification.notify(project)
    }

    private fun Notification.addSkillLocationActions(localSkillPath: Path?) {
        if (localSkillPath != null && Files.isDirectory(localSkillPath)) {
            addAction(
                NotificationAction.create(message("sdk.skill.notification.action.copyPath")) { _, notification ->
                    CopyPasteManager.getInstance().setContents(StringSelection(localSkillPath.toString()))
                    notification.expire()
                },
            )
            addAction(
                NotificationAction.create(message("sdk.skill.notification.action.revealFolder")) { _, notification ->
                    RevealFileAction.openFile(localSkillPath)
                    notification.expire()
                },
            )
        } else {
            addAction(
                NotificationAction.create(message("sdk.skill.notification.action.openOnline")) { _, notification ->
                    BrowserUtil.browse(SKILL_ONLINE_URL)
                    notification.expire()
                },
            )
        }
    }
}

internal const val BUNDLED_SKILL_RELATIVE_PATH: String = "agent-skills/workspace-mcp-rest-api"
internal const val SKILL_ONLINE_URL: String =
    "https://github.com/ghostflyby/IntelliJ-Plugins/tree/main/.agents/skills/workspace-mcp-rest-api"

internal fun shouldNotifySkill(
    currentVersion: String,
    notifiedVersion: String,
): Boolean = currentVersion != "unknown" && currentVersion != notifiedVersion

internal fun shouldRunSkillNotificationActivity(): Boolean {
    return !ApplicationManager.getApplication().isUnitTestMode
}

private fun bundledSkillPath(): Path {
    val plugin = PluginInfo.pluginDescriptor
    val path = plugin.pluginPath / BUNDLED_SKILL_RELATIVE_PATH
    return path
}
