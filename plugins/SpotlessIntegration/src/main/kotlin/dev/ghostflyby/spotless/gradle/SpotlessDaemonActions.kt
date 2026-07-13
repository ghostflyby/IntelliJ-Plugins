/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless.gradle

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import dev.ghostflyby.spotless.SpotlessProjectService
import dev.ghostflyby.spotless.spotlessNotificationGroupId

internal class StopSpotlessDaemonsAction : SpotlessDaemonLifecycleAction() {
    override fun notify(project: Project, count: Int) {
        notify(project, count, "Stopped")
    }
}

internal class RestartSpotlessDaemonsOnNextFormatAction : SpotlessDaemonLifecycleAction() {
    override fun notify(project: Project, count: Int) {
        notify(project, "Stopped $count Spotless daemon(s). They will restart on the next format request.")
    }
}

internal abstract class SpotlessDaemonLifecycleAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        event.presentation.isEnabledAndVisible =
            project != null && project.service<SpotlessProjectService>().hasRunningDaemons()
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val count = project.service<SpotlessProjectService>().releaseAllDaemons()
        notify(project, count)
    }

    protected abstract fun notify(project: Project, count: Int)

    protected fun notify(
        project: Project,
        count: Int,
        verb: String,
    ) {
        notify(project, "$verb $count Spotless daemon(s).")
    }

    protected fun notify(
        project: Project,
        message: String,
    ) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(spotlessNotificationGroupId)
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }
}
