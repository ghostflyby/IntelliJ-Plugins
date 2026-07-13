/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless.gradle

import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import org.gradle.util.GradleVersion
import org.jetbrains.plugins.gradle.service.task.GradleTaskManagerExtension
import org.jetbrains.plugins.gradle.settings.GradleExecutionSettings
import kotlin.io.path.Path


internal class SpotlessGradleTaskManagerExtension : GradleTaskManagerExtension {
    override fun configureTasks(
        projectPath: String,
        id: ExternalSystemTaskId,
        settings: GradleExecutionSettings,
        gradleVersion: GradleVersion?,
    ) {
        id.project.service<SpotlessGradleStateHolder>().isSpotlessEnabledForProjectDir(Path(projectPath)) || return
        val persistent = id.project.service<SpotlessGradleStateHolder>()
        val daemonVersion = persistent.gradleDaemonVersion.trim()
        val daemonJar = persistent.gradleDaemonJar.trim()

        settings.addInitScript(
            "dev.ghostflyby.spotless.daemon",
            spotlessDaemonInitScript(daemonVersion, daemonJar),
        )
    }
}
