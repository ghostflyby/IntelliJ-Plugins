/*
 * Copyright (c) 2025 ghostflyby <ghostflyby+intellij@outlook.com>
 *
 * This program is free software; you can redistribute it and/or
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
package dev.ghostflyby.dcevm

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import org.gradle.util.GradleVersion
import org.jetbrains.plugins.gradle.service.execution.toGroovyStringLiteral
import org.jetbrains.plugins.gradle.service.task.GradleTaskManagerExtension
import org.jetbrains.plugins.gradle.settings.GradleExecutionSettings

internal class DCEVMGradleManagerExtension : GradleTaskManagerExtension {
    override fun configureTasks(
        projectPath: String,
        id: ExternalSystemTaskId,
        settings: GradleExecutionSettings,
        gradleVersion: GradleVersion?,
    ) {

        val path =
            PathManager.getJarPathForClass(DCEVMGradleManagerExtension::class.java) ?: return

        settings.addInitScript(
            "ghostflyby.intellij.gradle.dcevm",
            """
initscript{
dependencies {
    classpath files(${path.toGroovyStringLiteral()})
}
}
pluginManager.apply(dev.ghostflyby.dcevm.IntelliJDcevmGradlePlugin)
""".trimIndent()
        )
    }

    override fun executeTasks(
        projectPath: String,
        id: ExternalSystemTaskId,
        settings: GradleExecutionSettings,
        listener: ExternalSystemTaskNotificationListener,
    ): Boolean {
        val project = id.project
        val resolved = effectiveHotSwapConfig(settings, project)
        settings.addEnvironmentVariable(ENABLE_DCEVM_ENV_KEY, resolved.enable.toString())
        settings.addEnvironmentVariable(ENABLE_HOTSWAP_AGENT_ENV_KEY, resolved.enableHotswapAgent.toString())
        return false
    }

}
