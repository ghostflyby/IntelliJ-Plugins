/*
 * Copyright (c) 2025 ghostflyby
 * SPDX-FileCopyrightText: 2025 ghostflyby
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

package dev.ghostflyby.spotless

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.externalSystem.util.task.TaskExecutionSpec
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import dev.ghostflyby.spotless.gradle.gradleRoot
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.nio.file.Path


internal val SPOTLESS_IN_PROJECT_KEY = Key.create<Boolean>("project.spotless")


internal val Project.isSpotlessEnabled: Boolean
    get() = getUserData(SPOTLESS_IN_PROJECT_KEY) == true

internal fun runGradleSpotlessDaemon(module: Module, unixSocketPath: Path) {
    val settings = ExternalSystemTaskExecutionSettings().apply {
        externalSystemIdString = GradleConstants.SYSTEM_ID.id
        externalProjectPath = module.gradleRoot?.toString()
        scriptParameters = "-Pdev.ghostflyby.spotless.daemon.unixsocket=${unixSocketPath.toAbsolutePath()}"
    }
    val exe = TaskExecutionSpec.create()
        .withProject(module.project)
        .withSettings(settings)
        .withExecutorId(DefaultRunExecutor.EXECUTOR_ID)
        .build()
    ExternalSystemUtil.runTask(exe)
}


internal const val spotlessNotificationGroupId = "Spotless Notifications"