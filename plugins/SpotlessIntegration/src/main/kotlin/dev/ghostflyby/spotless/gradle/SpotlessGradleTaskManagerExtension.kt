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

package dev.ghostflyby.spotless.gradle

import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import org.gradle.util.GradleVersion
import org.jetbrains.plugins.gradle.service.execution.toGroovyStringLiteral
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

        val s = @Suppress("SpellCheckingInspection")
        $$"""
            gradle.allprojects { proj ->
                proj.buildscript {
                    repositories {
                        gradlePluginPortal()
                    }
                    dependencies {
                        def daemonJar = $${daemonJar.toGroovyStringLiteral()}
                        def daemonVersion = $${daemonVersion.toGroovyStringLiteral()}
                        if (daemonJar) {
                            classpath files(daemonJar)
                        } else {
                            def resolved = daemonVersion ? daemonVersion : '0.5.4'
                            classpath "dev.ghostflyby.spotless.daemon:dev.ghostflyby.spotless.daemon.gradle.plugin:$resolved"
                        }
                    }
                }

                proj.pluginManager.withPlugin("com.diffplug.spotless") {
                    proj.apply plugin: "dev.ghostflyby.spotless.daemon"
                }
            }
        """.trimIndent()
        settings.addInitScript(
            "dev.ghostflyby.spotless.daemon",
            s
        )
    }
}
