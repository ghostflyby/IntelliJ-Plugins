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

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.Key
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.model.project.ExternalEntityData
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.project.IdeModifiableModelsProvider
import com.intellij.openapi.externalSystem.service.project.manage.AbstractProjectDataService
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.externalSystem.util.task.TaskExecutionSpec
import org.gradle.api.Project
import org.gradle.tooling.model.idea.IdeaModule
import org.jetbrains.plugins.gradle.service.project.AbstractProjectResolverExtension
import org.jetbrains.plugins.gradle.service.project.GradleProjectResolverExtension
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.nio.file.Path
import kotlin.io.path.absolute


internal data class SpotlessGradleStateData(
    val projectDirectory: Path,
    val spotless: Boolean,
) : ExternalEntityData {
    override fun getOwner(): ProjectSystemId {
        return ProjectSystemId("GRADLE")
    }

    companion object Companion {
        val KEY: Key<SpotlessGradleStateData> = Key.create(SpotlessGradleStateData::class.java, 1)
    }
}

@SpotlessIntegrationPluginInternalApi
internal class SpotlessGradleProjectResolverExtension : AbstractProjectResolverExtension(),
    GradleProjectResolverExtension {
    override fun populateModuleExtraModels(
        gradleModule: IdeaModule,
        ideModule: DataNode<ModuleData>,
    ) {
        val myModel = resolverCtx.getExtraProject(gradleModule, SpotlessGradleState::class.java)
        if (myModel != null) {
            ideModule.createChild(
                SpotlessGradleStateData.KEY,
                SpotlessGradleStateData(
                    gradleModule.gradleProject.projectDirectory.toPath().absolute(),
                    myModel.spotless,
                ),
            )
        }
        nextResolver.populateModuleExtraModels(gradleModule, ideModule)
    }


    override fun getExtraProjectModelClasses() = setOf(SpotlessGradleState::class.java)

    override fun getToolingExtensionsClasses() = setOf(SpotlessGradleStateModelBuilder::class.java)

}


internal class SpotlessGradleStateDataService : AbstractProjectDataService<SpotlessGradleStateData, Project>() {

    override fun getTargetDataKey() = SpotlessGradleStateData.KEY

    override fun importData(
        toImport: MutableCollection<out DataNode<SpotlessGradleStateData>>,
        projectData: ProjectData?,
        project: com.intellij.openapi.project.Project,
        modelsProvider: IdeModifiableModelsProvider,
    ) {
        val holder = service<SpotlessGradleStateHolder>()
        holder.updateFrom(toImport)
    }
}

@Service
internal class SpotlessGradleStateHolder {

    private val dataByModuleId = mutableMapOf<Path, SpotlessGradleStateData>()

    fun updateFrom(nodes: Collection<DataNode<SpotlessGradleStateData>>) {
        dataByModuleId.clear()
        nodes.forEach { node ->
            val value = node.data
            dataByModuleId[value.projectDirectory] = value
        }
    }

    fun isSpotlessEnabledForProjectDir(projectDir: Path): Boolean {
        return dataByModuleId[projectDir]?.spotless == true
    }

}

internal fun runGradleSpotlessDaemon(
    project: com.intellij.openapi.project.Project,
    externalProject: Path,
    unixSocketPath: Path,
) {
    val settings = ExternalSystemTaskExecutionSettings().apply {
        externalSystemIdString = GradleConstants.SYSTEM_ID.id
        externalProjectPath = externalProject.toString()
        scriptParameters = "-Pdev.ghostflyby.spotless.daemon.unixsocket=${unixSocketPath.toAbsolutePath()}"
    }
    val exe = TaskExecutionSpec.create()
        .withProject(project)
        .withSettings(settings)
        .withExecutorId(DefaultRunExecutor.EXECUTOR_ID)
        .build()
    ExternalSystemUtil.runTask(exe)
}


